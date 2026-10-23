package com.alrasin.jarvis.brain

import com.alrasin.jarvis.brain.cloud.CloudHttpException
import com.alrasin.jarvis.brain.cloud.OpenAiProvider
import com.alrasin.jarvis.brain.cloud.Preset
import com.alrasin.jarvis.brain.cloud.ProviderChain
import com.alrasin.jarvis.core.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OpenAI-style providers (Groq, OpenRouter, Cloudflare) and the provider chain (D-063). */
class OpenAiCloudTest {
    private fun body(preset: Preset, maxTokens: Int = 120) =
        Json.parse(OpenAiProvider.request(preset, "sys", listOf(Exchange("hi", "hello")), "what time", maxTokens, stream = true)) as Map<*, *>

    @Test fun requestHasRolesStreamAndTokens() {
        val body = body(Preset.cloudflare("acc"))
        assertEquals(listOf("system", "user", "assistant", "user"), (body["messages"] as List<*>).map { (it as Map<*, *>)["role"] })
        assertEquals(true, body["stream"])
        assertEquals(120L, (body["max_tokens"] as Number).toLong())
        assertEquals("@cf/meta/llama-3.3-70b-instruct-fp8-fast", body["model"])
    }

    @Test fun thinkingModelsGetExtraTokensAndTheirOwnModel() {
        val groq = body(Preset.GROQ)
        assertEquals("openai/gpt-oss-120b", groq["model"])
        assertEquals("low", groq["reasoning_effort"])
        assertEquals(520L, (groq["max_tokens"] as Number).toLong())
        assertEquals(mapOf("exclude" to true), body(Preset.OPENROUTER)["reasoning"])
    }

    @Test fun cloudflareUrlHasAccount() {
        assertEquals(
            "https://api.cloudflare.com/client/v4/accounts/abc123/ai/v1/chat/completions",
            Preset.cloudflare(" abc123 ").url,
        )
    }

    @Test fun parseAndStreamLines() {
        assertEquals("Paris.", OpenAiProvider.parse("""{"choices":[{"message":{"role":"assistant","content":" Paris. "}}]}"""))
        assertEquals("Par", OpenAiProvider.sseText("""data: {"choices":[{"delta":{"content":"Par"}}]}"""))
        assertNull(OpenAiProvider.sseText("data: [DONE]"))
        assertNull(OpenAiProvider.sseText("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(OpenAiProvider.sseText("""data: {"choices":[{"delta":{"reasoning":"hmm"}}]}"""))
        assertNull(OpenAiProvider.sseText(""))
    }

    @Test fun chainOrderGroqFirst() {
        assertEquals("Groq+Gemini+OpenRouter+Cloudflare",
            CloudBrain.chain(CloudKeys("AIzaTest", "gsk_x", "sk-or-x", "cf", "acc"))?.name)
        assertEquals("Gemini+OpenRouter", CloudBrain.chain(CloudKeys(main = "AIzaTest", openRouter = "sk-or-x"))?.name)
        assertEquals("Gemini", CloudBrain.chain(CloudKeys(main = "AIzaTest"))?.name)
        assertNull(CloudBrain.chain(CloudKeys(cloudflare = "cf")))   // no account ID → Cloudflare skipped
        assertNull(CloudBrain.chain(CloudKeys()))
    }

    @Test fun keysNeverPrinted() {
        assertTrue("gsk_secret" !in CloudKeys(groq = "gsk_secret").toString())
    }

    private class Fake(
        override val name: String, private val fail: Boolean, private val pieces: List<String> = listOf("ok"),
        private val code: Int? = null,
    ) : CloudProvider {
        var calls = 0
        private fun boom(): Nothing = if (code != null) throw CloudHttpException(name, code) else error("$name down")
        override suspend fun chat(system: String, history: List<Exchange>, text: String): String {
            calls++
            return if (fail) boom() else pieces.joinToString("")
        }
        override suspend fun stream(system: String, history: List<Exchange>, text: String, maxTokens: Int, onText: (String) -> Unit): String {
            calls++
            pieces.forEach(onText)
            if (fail) boom()
            return pieces.joinToString("")
        }
    }

    @Test fun backupAnswersWhenMainFails() = runBlocking {
        val p = ProviderChain(listOf(Fake("A", fail = true, pieces = emptyList()), Fake("B", fail = false, pieces = listOf("from B"))))
        assertEquals("from B", p.quick("s", emptyList(), "t", 10))
        val got = mutableListOf<String>()
        assertEquals("from B", p.stream("s", emptyList(), "t", 10) { got += it })
        assertEquals(listOf("from B"), got)
    }

    @Test fun thirdAnswersWhenTwoFail() = runBlocking {
        val p = ProviderChain(listOf(Fake("A", true), Fake("B", true), Fake("C", false, listOf("from C"))))
        assertEquals("from C", p.quick("s", emptyList(), "t", 10))
    }

    @Test fun limitReachedModelRestsThenComesBack() = runBlocking {
        var clock = 0L
        val a = Fake("A", fail = true, code = 429)
        val b = Fake("B", fail = false)
        val p = ProviderChain(listOf(a, b), restMs = 1000, now = { clock })
        p.quick("s", emptyList(), "t", 10)
        p.quick("s", emptyList(), "t", 10)
        assertEquals(1, a.calls)   // skipped while resting
        clock = 2000
        p.quick("s", emptyList(), "t", 10)
        assertEquals(2, a.calls)
    }

    @Test(expected = IllegalStateException::class)
    fun streamThatAlreadySpokeIsNotRetried() = runBlocking<Unit> {
        ProviderChain(listOf(Fake("A", fail = true, pieces = listOf("half")), Fake("B", fail = false))).stream("s", emptyList(), "t", 10) {}
    }
}

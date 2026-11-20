package com.alrasin.jimi.brain

import com.alrasin.jimi.brain.cloud.CloudHttp
import com.alrasin.jimi.brain.cloud.CloudHttpException
import com.alrasin.jimi.brain.cloud.OpenAiProvider
import com.alrasin.jimi.brain.cloud.Preset
import com.alrasin.jimi.brain.cloud.ProviderChain
import com.alrasin.jimi.core.Json
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

    @Test fun openAiUsesCompletionTokenField() {
        val body = body(Preset.OPENAI)
        assertEquals("gpt-5-nano", body["model"])
        assertEquals("minimal", body["reasoning_effort"])
        assertEquals(320L, (body["max_completion_tokens"] as Number).toLong())
        assertNull(body["max_tokens"])
        assertEquals("https://api.openai.com/v1/chat/completions", Preset.OPENAI.url)
    }

    @Test fun openAiIsLastInChain() {
        assertEquals("Groq+Gemini+OpenAI",
            CloudBrain.chain(CloudKeys(main = "AIzaTest", groq = "gsk_x", openAi = "sk-x"))?.name)
        assertEquals("OpenAI", CloudBrain.chain(CloudKeys(openAi = "sk-x"))?.name)
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
        // Own boxes: any Gemini key format is Gemini, never Claude
        assertEquals("Gemini", CloudBrain.chain(CloudKeys(gemini = "AQ.newformat"))?.name)
        assertEquals("Gemini+Claude", CloudBrain.chain(CloudKeys(gemini = "AQ.x", claude = "sk-ant-x"))?.name)
        assertNull(CloudBrain.chain(CloudKeys(cloudflare = "cf")))   // no account ID → Cloudflare skipped
        assertNull(CloudBrain.chain(CloudKeys()))
    }

    @Test fun keysNeverPrinted() {
        assertTrue("gsk_secret" !in CloudKeys(groq = "gsk_secret").toString())
    }

    private class Fake(
        override val name: String, private val fail: Boolean, private val pieces: List<String> = listOf("ok"),
        private val code: Int? = null, private val retryAfterMs: Long? = null,
    ) : CloudProvider {
        var calls = 0
        private fun boom(): Nothing = if (code != null) throw CloudHttpException(name, code, retryAfterMs) else error("$name down")
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

    @Test fun emptyAnswerTriesNext() = runBlocking {
        val p = ProviderChain(listOf(Fake("A", false, listOf("  ")), Fake("B", false, listOf("from B"))))
        assertEquals("from B", p.quick("s", emptyList(), "t", 10))
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

    @Test fun retryAfterHeaderSetsRestCappedAt300s() = runBlocking {
        var clock = 0L
        val a = Fake("A", fail = true, code = 429, retryAfterMs = 120_000)
        val b = Fake("B", fail = false)
        val p = ProviderChain(listOf(a, b), now = { clock })
        p.quick("s", emptyList(), "t", 10)
        clock = 119_000
        p.quick("s", emptyList(), "t", 10)
        assertEquals(1, a.calls)
        clock = 120_000
        p.quick("s", emptyList(), "t", 10)
        assertEquals(2, a.calls)
        assertEquals(300_000L, ProviderChain.restFor(3_600_000, 60_000))
        assertEquals(60_000L, ProviderChain.restFor(null, 60_000))
        assertEquals(5_000L, ProviderChain.restFor(5_000, 60_000))
    }

    @Test fun loneRestingModelFailsFastSoLocalAnswers() = runBlocking {
        var clock = 0L
        val a = Fake("Groq", fail = true, code = 429)
        val p = ProviderChain(listOf(a), now = { clock })
        repeat(2) { runCatching { p.quick("s", emptyList(), "t", 10) } }
        assertEquals(1, a.calls)   // second call never reached Groq
        clock = 60_000
        runCatching { p.quick("s", emptyList(), "t", 10) }
        assertEquals(2, a.calls)
        assertEquals("Groq", CloudBrain.chain(CloudKeys(groq = "gsk_x"))?.name)
    }

    @Test fun retryAfterParsing() {
        assertEquals(30_000L, CloudHttp.retryAfterMs(" 30 ", 0))
        assertNull(CloudHttp.retryAfterMs(null, 0))
        assertNull(CloudHttp.retryAfterMs("soon", 0))
        val now = java.time.ZonedDateTime.parse("Wed, 21 Oct 2026 07:28:00 GMT", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli()
        assertEquals(90_000L, CloudHttp.retryAfterMs("Wed, 21 Oct 2026 07:29:30 GMT", now))
    }

    @Test(expected = IllegalStateException::class)
    fun streamThatAlreadySpokeIsNotRetried() = runBlocking<Unit> {
        ProviderChain(listOf(Fake("A", fail = true, pieces = listOf("half")), Fake("B", fail = false))).stream("s", emptyList(), "t", 10) {}
    }
}

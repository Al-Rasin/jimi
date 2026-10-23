package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Json
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KimiCloudTest {
    @Test fun requestHasRolesAndThinkingOff() {
        val body = Json.parse(KimiProvider.request("sys", listOf(Exchange("hi", "hello")), "what time", 120, stream = true)) as Map<*, *>
        assertEquals(listOf("system", "user", "assistant", "user"), (body["messages"] as List<*>).map { (it as Map<*, *>)["role"] })
        assertEquals("disabled", (body["thinking"] as Map<*, *>)["type"])
        assertEquals(true, body["stream"])
        assertEquals(120L, (body["max_tokens"] as Number).toLong())
    }

    @Test fun parseAndStreamLines() {
        assertEquals("Paris.", KimiProvider.parse("""{"choices":[{"message":{"role":"assistant","content":" Paris. "}}]}"""))
        assertEquals("Par", KimiProvider.sseText("""data: {"choices":[{"delta":{"content":"Par"}}]}"""))
        assertNull(KimiProvider.sseText("data: [DONE]"))
        assertNull(KimiProvider.sseText("""data: {"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertNull(KimiProvider.sseText(""))
    }

    @Test fun combinePicksMainThenKimi() {
        assertEquals("Gemini+Kimi", CloudBrain.combine("AIzaTest", "sk-kimi")?.name)
        assertEquals("Kimi", CloudBrain.combine(null, "sk-kimi")?.name)
        assertEquals("Gemini", CloudBrain.combine("AIzaTest", null)?.name)
        assertNull(CloudBrain.combine(null, null))
    }

    private class Fake(override val name: String, private val fail: Boolean, private val pieces: List<String> = listOf("ok")) : CloudProvider {
        override suspend fun chat(system: String, history: List<Exchange>, text: String): String =
            if (fail) error("$name down") else pieces.joinToString("")
        override suspend fun stream(system: String, history: List<Exchange>, text: String, maxTokens: Int, onText: (String) -> Unit): String {
            pieces.forEach(onText)
            if (fail) error("$name down")
            return pieces.joinToString("")
        }
    }

    @Test fun backupAnswersWhenMainFails() = runBlocking {
        val p = FallbackProvider(Fake("A", fail = true, pieces = emptyList()), Fake("B", fail = false, pieces = listOf("from B")))
        assertEquals("from B", p.quick("s", emptyList(), "t", 10))
        val got = mutableListOf<String>()
        assertEquals("from B", p.stream("s", emptyList(), "t", 10) { got += it })
        assertEquals(listOf("from B"), got)
    }

    @Test(expected = IllegalStateException::class)
    fun streamThatAlreadySpokeIsNotRetried() = runBlocking<Unit> {
        FallbackProvider(Fake("A", fail = true, pieces = listOf("half")), Fake("B", fail = false)).stream("s", emptyList(), "t", 10) {}
    }
}

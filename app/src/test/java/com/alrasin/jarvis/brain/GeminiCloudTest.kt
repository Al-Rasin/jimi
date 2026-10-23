package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ArgSpec
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.core.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCloudTest {
    private fun tool(name: String, vararg args: ArgSpec) = object : Tool {
        override val name = name
        override val description = "test $name"
        override val args = args.toList()
        override val risk = Risk.SAFE
        override suspend fun run(call: ToolCall, ctx: ToolContext) = ToolResult.ok("")
    }

    private val tools = listOf(
        tool("set_alarm", ArgSpec("time", "alarm time"), ArgSpec("label", "label", required = false)),
        tool("flashlight", ArgSpec("state", "on or off", choices = listOf("on", "off"))),
    )

    @Test fun requestHasSystemHistoryAndMinimalThinking() {
        val body = Json.parse(GeminiProvider.request("sys", listOf(Exchange("hi", "hello")), "what time", 120)) as Map<*, *>
        val contents = body["contents"] as List<*>
        assertEquals(listOf("user", "model", "user"), contents.map { (it as Map<*, *>)["role"] })
        val config = body["generationConfig"] as Map<*, *>
        assertEquals("minimal", (config["thinkingConfig"] as Map<*, *>)["thinkingLevel"])
        assertEquals(120L, (config["maxOutputTokens"] as Number).toLong())
        assertTrue(Json.write(body["systemInstruction"]).contains("sys"))
    }

    @Test fun parseJoinsTextAndSkipsThoughts() {
        val body = """{"candidates":[{"content":{"role":"model","parts":[
            {"text":"plan","thought":true},{"text":"Hello ","thoughtSignature":"abc"},{"text":"Boss."}]}}]}"""
        assertEquals("Hello Boss.", GeminiProvider.parse(body))
    }

    @Test(expected = IllegalStateException::class)
    fun parseFailsWithoutCandidates() {
        GeminiProvider.parse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }

    @Test fun keyPrefixPicksProvider() {
        assertEquals("Gemini", CloudBrain.providerFor("AIzaTest").name)
        assertEquals("Claude", CloudBrain.providerFor("sk-ant-test").name)
    }

    @Test fun toolCallParsesValidCallInsideFence() {
        val call = CloudBrain.parseToolCall("```json\n{\"tool\":\"set_alarm\",\"args\":{\"time\":\"7 am\",\"x\":\"1\"}}\n```", tools)
        assertEquals(ToolCall("set_alarm", mapOf("time" to "7 am")), call)
    }

    @Test fun toolCallNoneMeansChat() {
        assertEquals(CloudBrain.NONE, CloudBrain.parseToolCall("{\"tool\":\"none\"}", tools))
    }

    @Test fun toolCallRejectsUnknownMissingOrBadChoice() {
        assertNull(CloudBrain.parseToolCall("{\"tool\":\"delete_all\",\"args\":{}}", tools))
        assertNull(CloudBrain.parseToolCall("{\"tool\":\"set_alarm\",\"args\":{}}", tools))
        assertNull(CloudBrain.parseToolCall("{\"tool\":\"flashlight\",\"args\":{\"state\":\"blink\"}}", tools))
        assertNull(CloudBrain.parseToolCall("sorry", tools))
    }

    @Test fun toolPromptListsArgsAndChoices() {
        val p = CloudBrain.toolPrompt(tools)
        assertTrue(p.contains("- set_alarm: test set_alarm Args: time = alarm time; label (optional) = label"))
        assertTrue(p.contains("one of on|off"))
    }

    @Test fun noneCanCarryASpokenAnswer() {
        val call = CloudBrain.parseToolCall("{\"tool\":\"none\",\"say\":\"Paris is the capital of France, Boss.\"}", tools)
        assertEquals(ToolCall("none", mapOf("say" to "Paris is the capital of France, Boss.")), call)
        assertEquals(CloudBrain.NONE, CloudBrain.parseToolCall("{\"tool\":\"none\",\"say\":\"  \"}", tools))
    }

    @Test fun toolPromptAsksForSayOnChat() {
        assertTrue(CloudBrain.toolPrompt(tools).contains("\"say\""))
    }

    @Test fun sseLinesGiveTextPieces() {
        assertEquals("Hello ", GeminiProvider.sseText("data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello \"}]}}]}"))
        assertNull(GeminiProvider.sseText(""))
        assertNull(GeminiProvider.sseText(": keep-alive"))
        assertNull(GeminiProvider.sseText("data: {\"usageMetadata\":{}}"))
    }
}

package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test

class CallRulesTest {
    private fun tool(text: String) = RuleMatcher.match(text)?.tool

    @Test fun answerAndReject() {
        listOf("answer", "Jarvis, answer the call", "pick up", "pick up the phone", "accept the call", "answer the WhatsApp call")
            .forEach { assertEquals(it, "answer_call", tool(it)) }
        listOf("reject", "decline the call", "cut the call", "hang up", "end the call", "disconnect the call")
            .forEach { assertEquals(it, "reject_call", tool(it)) }
    }

    @Test fun callLog() {
        listOf("missed calls", "any missed calls?", "who called me", "did anyone call me").forEach { assertEquals(it, "missed_calls", tool(it)) }
        listOf("call back", "call him back", "call back the last caller", "return the call").forEach { assertEquals(it, "call_back", tool(it)) }
        listOf("redial", "call the last number again", "call again").forEach { assertEquals(it, "redial", tool(it)) }
    }

    @Test fun callingANameStillWorks() {
        assertEquals(ToolCall("call", mapOf("contact" to "rahim")), RuleMatcher.match("call rahim"))
        assertEquals("call", tool("call Bakery"))
    }
}

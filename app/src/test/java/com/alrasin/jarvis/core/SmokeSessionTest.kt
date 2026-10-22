package com.alrasin.jarvis.core

import org.junit.Assert.*
import org.junit.Test

class SmokeSessionTest {
    @Test fun deniesRiskyAndUnknownToolsEvenInMutableSuite() {
        for (name in listOf("call", "send_sms", "send_whatsapp", "pay", "tap", "type_text",
            "open_url", "run_routine", "panic", "unlock_phone", "new_unknown_tool")) {
            assertFalse(SmokeSession.allows(ToolCall(name), false))
        }
    }
    @Test fun onlyAllowsValidatedReversibleActions() {
        assertTrue(SmokeSession.allows(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "off")), false))
        assertFalse(SmokeSession.allows(ToolCall("toggle", mapOf("target" to "wifi", "state" to "off")), false))
        assertFalse(SmokeSession.allows(ToolCall("set_volume", mapOf("level" to "101")), false))
        assertFalse(SmokeSession.allows(ToolCall("open_app", mapOf("app" to "bank")), false))
    }
    @Test fun readOnlyBlocksMutationAndMarksFailure() {
        val trace = SmokeSession(true)
        assertNull(trace.intercept(ToolCall("device_status")))
        assertFalse(trace.intercept(ToolCall("set_volume", mapOf("level" to "50")))!!.ok)
        assertTrue(trace.dryRun)
    }
    @Test fun simulatedDangerousActionIsNotARealSuccess() {
        val trace = SmokeSession(false)
        assertTrue(trace.intercept(ToolCall("send_sms"))!!.say.startsWith("Dry run:"))
        assertTrue(trace.dryRun)
    }
    @Test fun recordIsOneJsonLineWithRequiredFields() {
        val trace = SmokeSession(true)
        trace.call = ToolCall("device_status", mapOf("what" to "time"))
        val json = trace.record("id-1", "what's the time", "a\nb", 12)
        assertFalse(json.contains('\n'))
        val parsed = Json.parse(json) as Map<*, *>
        assertEquals("id-1", parsed["id"])
        assertEquals("a\nb", parsed["reply"])
        assertEquals(setOf("id", "heard", "route", "tool", "args", "ok", "reply", "ms", "dry_run", "read_only", "truncated"), parsed.keys)
    }
    @Test fun longUnicodeAndControlCharactersStayWithinLogcatLimit() {
        val trace = SmokeSession(true)
        trace.call = ToolCall("read_screen", mapOf("text" to "界".repeat(3000)))
        val json = trace.record("id-1", "\u0001".repeat(240), "界".repeat(2000), 12)
        assertTrue(json.toByteArray(Charsets.UTF_8).size < 3500)
        assertEquals(true, (Json.parse(json) as Map<*, *>)["truncated"])
    }

}

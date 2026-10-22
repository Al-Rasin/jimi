package com.alrasin.jarvis.core

import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.ToolSafetyGate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmokeDryRunTest {
    @Test fun explicitDryRunInterceptsEveryToolIncludingMutableAllowlist() {
        val calls = listOf(
            ToolCall("call", mapOf("contact" to "placeholder", "app" to "whatsapp")),
            ToolCall("send_sms"), ToolCall("set_alarm"), ToolCall("set_timer"), ToolCall("cancel_alarm"),
            ToolCall("play_media"), ToolCall("web_search"), ToolCall("device_status"),
            ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")),
            ToolCall("set_volume", mapOf("level" to "50")),
            ToolCall("open_app", mapOf("app" to "settings")), ToolCall("unknown_future_tool"),
            ToolCall("screen_task"), ToolCall("run_routine"), ToolCall("panic"),
        )
        for (readOnly in listOf(true, false)) for (call in calls) {
            val trace = SmokeSession(readOnly, forceDryRun = true)
            val result = trace.intercept(call)
            assertNotNull(call.tool, result)
            assertTrue(result!!.ok)
            assertTrue(result.say.contains("not executed"))
        }
    }

    @Test fun confirmationAndConfirmedDryRunNeverInvokeRealAction() = runBlocking {
        val trace = SmokeSession(true, forceDryRun = true)
        val call = ToolCall("call", mapOf("contact" to "placeholder"))
        var realActions = 0
        val gate = ToolSafetyGate()
        suspend fun execute(confirmed: Boolean) = gate.execute(call, Risk.CONFIRM, false, confirmed, false) {
            trace.intercept(call) ?: run { realActions++; ToolResult(true, "executed") }
        }
        assertTrue(execute(false) is Outcome.NeedConfirm)
        assertTrue(execute(true) is Outcome.Done)
        assertEquals(0, realActions)
    }

    @Test fun clarificationBeforeInterceptionReportsDryRunAndOriginalArguments() {
        val trace = SmokeSession(true, forceDryRun = true)
        trace.route = "rule"
        trace.requestedCall = ToolCall("call", mapOf("contact" to "mom"))
        trace.call = trace.requestedCall
        val record = Json.parse(trace.record("choice", "call mom", "Which mom?", 5,
            expectAnswer = true, deviceTime = "2026-10-22T07:00:00")) as Map<*, *>
        assertEquals(true, record["dry_run"])
        assertEquals(false, record["executed"])
        assertEquals(true, record["expectAnswer"])
        assertEquals(mapOf("contact" to "mom"), record["requested_args"])
        assertEquals("2026-10-22T07:00:00", record["device_time"])
    }

    @Test fun originalArgumentsSurviveResolvedContactReplacement() {
        val trace = SmokeSession(true, forceDryRun = true)
        trace.requestedCall = ToolCall("call", mapOf("contact" to "amma"))
        trace.call = ToolCall("call", mapOf("contact" to "placeholder", "contact_name" to "Placeholder"))
        val record = Json.parse(trace.record("resolved", "call amma", "Confirm?", 1, true)) as Map<*, *>
        assertEquals(mapOf("contact" to "amma"), record["requested_args"])
        assertEquals(trace.call!!.args, record["args"])
    }
}

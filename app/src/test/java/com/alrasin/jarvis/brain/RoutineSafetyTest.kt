package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineSafetyTest {
    @Test fun confirmationStopsBeforeLaterSteps() = runBlocking {
        val calls = mutableListOf<String>()
        val result = RoutineRunner.run(
            listOf(ToolCall("device_status"), ToolCall("send_sms"), ToolCall("delete_reminder")),
            execute = { call, confirmed ->
                assertFalse(confirmed)
                calls.add(call.tool)
                if (call.tool == "send_sms") Outcome.NeedConfirm("Send the message?")
                else Outcome.Done(ToolResult.ok("Okay."))
            },
            record = { _, _ -> },
        )
        assertEquals(listOf("device_status", "send_sms"), calls)
        assertTrue(result!!.contains("Routine stopped"))
        assertTrue(result.contains("Request that action separately"))
    }

    @Test fun denialStopsAndReportsReason() = runBlocking {
        val calls = mutableListOf<String>()
        val recorded = mutableListOf<ToolResult>()
        val result = RoutineRunner.run(
            listOf(ToolCall("unlock_phone"), ToolCall("send_sms")),
            execute = { call, confirmed ->
                assertFalse(confirmed)
                calls.add(call.tool)
                Outcome.Denied("Voice verification is unavailable.")
            },
            record = { _, outcome -> recorded.add(outcome) },
        )
        assertEquals(listOf("unlock_phone"), calls)
        assertEquals("Routine stopped. Voice verification is unavailable.", result)
        assertFalse(recorded.single().ok)
    }

    @Test fun lockChangeStopsBeforeReusingOldSecurityState() = runBlocking {
        for (lockTool in listOf("lock_phone", "unlock_phone")) {
            val calls = mutableListOf<String>()
            val result = RoutineRunner.run(
                listOf(ToolCall(lockTool), ToolCall("read_notes")),
                execute = { call, _ ->
                    calls.add(call.tool)
                    Outcome.Done(ToolResult.ok("Okay."))
                },
                record = { _, _ -> },
            )
            assertEquals(listOf(lockTool), calls)
            assertTrue(result!!.contains("phone lock"))
        }
    }

    @Test fun safeStepsFinishWithoutConfirmation() = runBlocking {
        val recorded = mutableListOf<String>()
        val result = RoutineRunner.run(
            listOf(ToolCall("device_status"), ToolCall("weather")),
            execute = { _, confirmed ->
                assertFalse(confirmed)
                Outcome.Done(ToolResult.ok("Okay."))
            },
            record = { call, _ -> recorded.add(call.tool) },
        )
        assertNull(result)
        assertEquals(listOf("device_status", "weather"), recorded)
    }
}

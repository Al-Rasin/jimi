package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolGateSafetyTest {
    private class FakeAction {
        var runs = 0
        suspend fun run(): ToolResult {
            runs++
            return ToolResult.ok("Done.")
        }
    }

    @Test fun missingModelEnrollmentAudioOrFailedVoiceRefusesConfirmedRiskyAction() = runBlocking {
        // JarvisCoreService maps each unavailable/failed verification to false, not null.
        for (risk in listOf(Risk.CONFIRM, Risk.VOICE_ID)) {
            val action = FakeAction()
            val outcome = ToolSafetyGate().execute(ToolCall("send_sms"), risk, true, true, false, action::run)
            assertTrue(outcome is Outcome.Denied)
            assertTrue((outcome as Outcome.Denied).why.contains("verify your voice"))
            assertEquals(0, action.runs)
        }
    }

    @Test fun optionalVoiceIdOffStillRequiresConfirmation() = runBlocking {
        val gate = ToolSafetyGate()
        val action = FakeAction()
        assertTrue(gate.execute(ToolCall("call"), Risk.CONFIRM, true, false, null, action::run) is Outcome.NeedConfirm)
        assertEquals(0, action.runs)
        assertTrue(gate.execute(ToolCall("call"), Risk.CONFIRM, true, true, null, action::run) is Outcome.Done)
        assertEquals(1, action.runs)
    }

    @Test fun panicBlocksEvenConfirmedOwnerAndUnlockedDebugContext() = runBlocking {
        val gate = ToolSafetyGate()
        val action = FakeAction()
        gate.enterLockdown()
        for (locked in listOf(true, false)) {
            for (voice in listOf(true, false, null)) {
                val outcome = gate.execute(ToolCall("unlock_phone"), Risk.VOICE_ID, locked, true, voice, action::run)
                assertTrue(outcome is Outcome.Denied)
                assertTrue((outcome as Outcome.Denied).why.contains("Panic mode"))
            }
        }
        assertEquals(0, action.runs)
    }

    @Test fun panicClearsOnlyAfterObservedLockedThenUnlocked() = runBlocking {
        val gate = ToolSafetyGate()
        val action = FakeAction()
        gate.enterLockdown()
        gate.observeLockState(false)
        assertTrue(gate.execute(ToolCall("call"), Risk.CONFIRM, false, true, true, action::run) is Outcome.Denied)
        gate.observeLockState(true)
        gate.observeLockState(false)
        assertTrue(gate.execute(ToolCall("call"), Risk.CONFIRM, false, true, null, action::run) is Outcome.Done)
        assertEquals(1, action.runs)
    }

    @Test fun panicStillAllowsSafeLockAndStatusTools() = runBlocking {
        val gate = ToolSafetyGate()
        val action = FakeAction()
        gate.enterLockdown()
        for (name in listOf("lock_phone", "device_status")) {
            assertTrue(gate.execute(ToolCall(name), Risk.SAFE, true, false, false, action::run) is Outcome.Done)
        }
        assertEquals(2, action.runs)
    }

    @Test fun mislabelledDestructiveAndScreenActionsRequireConfirmation() = runBlocking {
        val gate = ToolSafetyGate()
        val action = FakeAction()
        for (name in listOf("delete_reminder", "forget_fact", "cancel_alarm")) {
            assertTrue(name, gate.execute(ToolCall(name), Risk.SAFE, false, false, null, action::run) is Outcome.NeedConfirm)
            assertTrue(name, gate.execute(ToolCall(name), Risk.SAFE, true, true, false, action::run) is Outcome.Denied)
        }
        assertEquals(0, action.runs)
    }

    @Test fun privateReadsRefuseFailedVerificationAndPanicButRemainSafeUnlocked() = runBlocking {
        val action = FakeAction()
        for (name in listOf("read_messages", "read_notes", "list_reminders", "recall_facts", "read_screen", "read_notifications", "notification_summary")) {
            val gate = ToolSafetyGate()
            assertTrue(name, gate.execute(ToolCall(name), Risk.SAFE, true, true, false, action::run) is Outcome.Denied)
            assertTrue(name, gate.execute(ToolCall(name), Risk.SAFE, false, false, null, action::run) is Outcome.Done)
            gate.enterLockdown()
            assertTrue(name, gate.execute(ToolCall(name), Risk.SAFE, false, true, true, action::run) is Outcome.Denied)
        }
        assertEquals(7, action.runs)
    }
}

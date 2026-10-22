package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.ToolCall
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmQuestionTest {
    private data class Case(
        val name: String,
        val risk: Risk = Risk.SAFE,
        val locked: Boolean = false,
        val args: Map<String, String> = emptyMap(),
    )

    private val cases = listOf(
        Case("call", Risk.CONFIRM, args = mapOf("contact" to "Mom")),
        Case("send_sms", Risk.CONFIRM, args = mapOf("contact" to "Mom", "text" to "hello")),
        Case("send_whatsapp", Risk.CONFIRM, args = mapOf("contact" to "Mom", "text" to "hello")),
        Case("unlock_phone", Risk.VOICE_ID),
        Case("clear_notifications", Risk.CONFIRM),
        Case("read_notifications", Risk.VOICE_ID),
        Case("notification_summary", Risk.VOICE_ID),
        Case("undo_notification_clear", Risk.VOICE_ID),
        Case("explain_notification_clear", Risk.VOICE_ID),
        Case("search_notification_history", Risk.VOICE_ID, args = mapOf("query" to "bank")),
        Case("delete_reminder", args = mapOf("text" to "milk")),
        Case("forget_fact", args = mapOf("text" to "tea preference")),
        Case("cancel_alarm"),
        Case("read_messages", locked = true),
        Case("read_notes", locked = true),
        Case("list_reminders", locked = true),
        Case("recall_facts", locked = true),
        Case("read_screen", locked = true),
    )

    @Test fun everyConfirmationToolAsksAShortSpokenQuestion() = runBlocking {
        for (case in cases) {
            val text = question(ToolCall(case.name, case.args), case.risk, case.locked)
            assertSpoken(text)
            assertTrue("${case.name}: $text", text.split(Regex("\\s+")).size <= 10)
        }
    }

    @Test fun absentOrBlankArgumentsNeverSpeakNull() = runBlocking {
        for (case in cases) {
            for (args in listOf(emptyMap(), mapOf("contact" to " ", "text" to " ", "query" to " "))) {
                val text = question(ToolCall(case.name, args), case.risk, case.locked)
                assertSpoken(text)
                assertFalse(text, text.contains("null", ignoreCase = true))
            }
        }
    }

    @Test fun callsNameTheRecipientAppAndVideoMode() = runBlocking {
        for (app in listOf("whatsapp", "messenger")) {
            val text = question(ToolCall("call", mapOf(
                "contact" to "+8801700000000", "contact_name" to "Mom", "app" to app, "video" to "true",
            )), Risk.CONFIRM)
            assertSpoken(text)
            assertTrue(text, text.contains("Mom"))
            assertTrue(text, text.contains(app, ignoreCase = true))
            assertTrue(text, text.contains("video", ignoreCase = true))
            assertFalse(text, text.contains("+8801700000000"))
        }
    }

    @Test fun messagesKeepTextRecipientAndApp() = runBlocking {
        for ((tool, app) in listOf("send_sms" to "messenger", "send_whatsapp" to "whatsapp")) {
            val text = question(ToolCall(tool, mapOf(
                "contact" to "+8801700000000", "contact_name" to "Mom", "text" to "on my way", "app" to app,
            )), Risk.CONFIRM)
            assertSpoken(text)
            assertTrue(text, text.contains("on my way"))
            assertTrue(text, text.contains("Mom"))
            assertTrue(text, text.contains(app, ignoreCase = true))
            assertFalse(text, text.contains("+8801700000000"))
        }
    }

    @Test fun remindersDistinguishAllFromASpecificReminder() = runBlocking {
        val all = question(ToolCall("delete_reminder", mapOf("text" to "all")))
        assertTrue(all, all.contains("all", ignoreCase = true))
        assertTrue(all, all.contains("reminders", ignoreCase = true))
        val specific = question(ToolCall("delete_reminder", mapOf("text" to "buy milk")))
        assertTrue(specific, specific.contains("buy milk"))
        assertFalse(specific, specific.contains("all reminders", ignoreCase = true))
    }

    @Test fun forgettingNeverPromisesUnsupportedBulkDeletion() = runBlocking {
        val specific = question(ToolCall("forget_fact", mapOf("text" to "I prefer tea")))
        assertTrue(specific, specific.contains("I prefer tea"))
        val all = question(ToolCall("forget_fact", mapOf("text" to "all")))
        assertSpoken(all)
        assertFalse(all, all.contains("all facts", ignoreCase = true))
        assertFalse(all, all.contains("all your facts", ignoreCase = true))
        assertFalse(all, all.contains("everything", ignoreCase = true))
    }

    @Test fun alarmConfirmationMatchesTheToolsActualScope() = runBlocking {
        val expected = "Cancel all your alarms and timers, ${Persona.ADDRESS}?"
        assertEquals(expected, question(ToolCall("cancel_alarm")))
        assertEquals(expected, question(ToolCall("cancel_alarm", mapOf("time" to "07:00"))))
    }

    @Test fun notificationSearchIncludesTheQuery() = runBlocking {
        val text = question(ToolCall("search_notification_history", mapOf("query" to "bank transfer")), Risk.VOICE_ID)
        assertTrue(text, text.contains("bank transfer"))
    }

    @Test fun fallbackAndArgumentFormattingRemainSpeakable() = runBlocking {
        assertSpoken(question(ToolCall("archive_old_items"), Risk.CONFIRM))
        val text = question(ToolCall("send_sms", mapOf("contact" to "my_mom", "text" to "on_my_way")), Risk.CONFIRM)
        assertSpoken(text)
        assertTrue(text, text.contains("my mom"))
        assertTrue(text, text.contains("on my way"))
    }

    @Test fun longMessagesKeepTheEntireTextForAnInformedConfirmation() = runBlocking {
        val message = "Please meet me outside the station after the last train arrives tonight"
        val text = question(ToolCall("send_sms", mapOf("contact" to "Mom", "text" to message)), Risk.CONFIRM)
        assertTrue(text, text.contains(message))
        assertSpoken(text)
    }

    private suspend fun question(call: ToolCall, risk: Risk = Risk.SAFE, locked: Boolean = false): String {
        val outcome = ToolSafetyGate().execute(call, risk, locked, confirmed = false, voiceOk = null) {
            error("${call.tool} ran before confirmation")
        }
        assertTrue("${call.tool}: $outcome", outcome is Outcome.NeedConfirm)
        return (outcome as Outcome.NeedConfirm).question
    }

    private fun assertSpoken(text: String) {
        assertFalse(text, text.contains('_'))
        assertFalse(text, text.startsWith("Do "))
        assertTrue(text, text.endsWith('?'))
        assertTrue(text, text.contains(Persona.ADDRESS))
        assertFalse(text, text.contains('\n'))
        assertFalse(text, text.contains('*') || text.contains('`'))
    }
}

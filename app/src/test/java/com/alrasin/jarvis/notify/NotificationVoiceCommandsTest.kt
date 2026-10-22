package com.alrasin.jarvis.notify

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationVoiceCommandsTest {
    @Test fun undoLastClearVariants() {
        assertCommands("undo_notification_clear", "undo last clear", "undo the last clear")
    }

    @Test fun undoNotificationClearVariants() {
        assertCommands("undo_notification_clear", "undo notification clear", "undo the notification clear")
    }

    @Test fun restoreClearedVariants() {
        assertCommands(
            "undo_notification_clear",
            "restore cleared notifications", "restore my cleared notifications", "restore the cleared notifications",
            "restore last cleared notifications", "restore my last cleared notifications", "restore the last cleared notifications",
        )
    }

    @Test fun explanationPronounVariants() {
        assertCommands(
            "explain_notification_clear",
            "why did you clear that", "why did you clear it", "why did you clear those",
            "why was that cleared", "why was it cleared",
        )
    }

    @Test fun explanationNotificationVariants() {
        assertCommands(
            "explain_notification_clear", "why did you clear notifications", "why did you clear my notifications",
            "why did you clear the notifications", "explain last clear", "explain the last clear",
        )
    }

    @Test fun capitalizationAndSentencePunctuation() {
        assertCommands("undo_notification_clear", "UNDO LAST CLEAR!", "Undo the last clear.", "Restore my cleared notifications?")
        assertCommands("explain_notification_clear", "WHY DID YOU CLEAR THAT?", "Why was that cleared?!", "Explain the last clear.")
    }

    @Test fun whitespaceAndWordPunctuation() {
        assertCommands("undo_notification_clear", "  undo    last  clear  ", "undo\tlast\nclear", "Undo, the last clear!")
        assertCommands("explain_notification_clear", "why\tdid\nyou clear that?", "Explain: the last clear.")
    }

    @Test fun politeCommandsAndWakeName() {
        assertCommands(
            "undo_notification_clear", "please undo last clear", "Hey Jimi, undo last clear please",
            "can you restore my cleared notifications", "could you undo the notification clear for me",
        )
        assertCommands("explain_notification_clear", "Jimi, why did you clear that?", "please explain the last clear")
    }

    @Test fun unrelatedChatDoesNotBecomeACommand() {
        assertNoCommands(
            "tell me a joke", "why is the sky clear", "what does undo last clear mean",
            "tell me why did you clear that", "we should undo last clear tomorrow",
            "I said undo last clear yesterday", "is restore cleared notifications a command",
        )
    }

    @Test fun negationDoesNotBecomeACommand() {
        assertNoCommands(
            "do not undo last clear", "don't undo last clear", "never restore my cleared notifications",
            "do not explain the last clear", "please don't undo notification clear",
        )
    }

    @Test fun otherUndoActionsAreNotCaptured() {
        assertNoCommands("undo", "undo last message", "undo last call", "undo last delete", "restore backup", "restore my notes")
    }

    @Test fun otherNotificationCommandsRemainForMainRouter() {
        assertNoCommands("clear notifications", "read notifications", "notification summary", "search notification history for bank")
    }

    @Test fun trailingActionsDoNotTriggerPartialMatch() {
        assertNoCommands(
            "undo last clear and call someone", "why did you clear that and send a message",
            "restore my cleared notifications tomorrow", "undo last clear all notifications",
        )
    }

    @Test fun incompleteOrDifferentTargetsAreRejected() {
        assertNoCommands(
            "undo clear", "why did you clear", "why was cleared", "restore notifications",
            "why did you clear my cache", "explain last", "why did you clear that message",
        )
    }

    @Test fun emptyPunctuationAndNonEnglishTextDoNotMatch() {
        assertNoCommands("", "  \t\n", "?!...", "নোটিফিকেশন দেখাও", "撤销通知", "🔔")
    }

    private fun assertCommands(tool: String, vararg inputs: String) {
        inputs.forEach { assertEquals(it, ToolCall(tool), NotificationVoiceCommands.match(it)) }
    }

    private fun assertNoCommands(vararg inputs: String) {
        inputs.forEach { assertNull(it, NotificationVoiceCommands.match(it)) }
    }
}

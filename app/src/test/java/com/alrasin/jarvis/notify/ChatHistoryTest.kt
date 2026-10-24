package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryTest {
    private fun n(t: Long, pkg: String, title: String, text: String, app: String = "WhatsApp") =
        NotificationLog(timeMs = t, pkg = pkg, appName = app, title = title, text = text, important = false, reason = "", status = "")

    @Test fun titleIsSender() {
        val log = listOf(n(1, "com.whatsapp", "Rahim Khan", "hi"), n(2, "com.whatsapp", "Karim", "yo"))
        val got = ChatHistory.lastMessages(log, "rahim", null, 1)
        assertEquals(listOf("hi"), got.map { it.text })
        assertEquals("Rahim Khan on WhatsApp said: hi", ChatHistory.say(got))
    }

    @Test fun groupLinesSummaryAndDedupe() {
        val log = listOf(
            n(1, "com.whatsapp", "Family (2 messages)", "Rahim: one\nKarim: x"),
            n(2, "com.whatsapp", "Family (3 messages)", "3 new messages\nRahim: one\nRahim: two"),
        )
        val got = ChatHistory.lastMessages(log, "rahim", "whatsapp", 5)
        assertEquals(listOf("one", "two"), got.map { it.text })
        assertEquals("Last 2 from Rahim on WhatsApp: 1, one. 2, two", ChatHistory.say(got))
    }

    @Test fun appFilter() {
        val log = listOf(n(1, "com.whatsapp", "Rahim", "wa"), n(2, "com.facebook.orca", "Rahim", "fb", "Messenger"))
        assertEquals(listOf("fb"), ChatHistory.lastMessages(log, "rahim", "messenger", 3).map { it.text })
        assertEquals(listOf("wa"), ChatHistory.lastMessages(log, "rahim", "whatsapp", 3).map { it.text })
        assertTrue(ChatHistory.lastMessages(log, "rahim", "telegram", 3).isEmpty())
        assertTrue(ChatHistory.lastMessages(listOf(n(1, "com.other", "Rahim", "x")), "rahim", null, 3).isEmpty())
    }

    @Test fun sameNameBothWays() {
        assertTrue(ChatHistory.sameName("Rahim Khan", "rahim"))
        assertTrue(ChatHistory.sameName("Rahim", "rahim khan"))
        assertFalse(ChatHistory.sameName("Karim", "rahim"))
        assertFalse(ChatHistory.sameName("", "rahim"))
    }

    @Test fun lastIncomingDropsMineAndTimes() {
        val b = listOf(
            ChatHistory.Bubble("Yesterday", false), ChatHistory.Bubble("hello", false),
            ChatHistory.Bubble("my reply", true), ChatHistory.Bubble("10:42 pm", false), ChatHistory.Bubble("ok", false),
        )
        assertEquals(listOf("hello", "ok"), ChatHistory.lastIncoming(b, 5))
        assertEquals(listOf("ok"), ChatHistory.lastIncoming(b, 1))
    }
}

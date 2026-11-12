package com.alrasin.jarvis.notify

import com.alrasin.jarvis.data.NotificationLog
import org.junit.Assert.assertEquals
import org.junit.Test

class DailySummaryTest {
    private var id = 0L
    private fun n(pkg: String, app: String, title: String, time: Long = id, important: Boolean = true) =
        NotificationLog(++id, time, pkg, app, title, "hi", important, "test", "kept", null)

    @Test fun nothing() {
        assertEquals("Nothing missed.", DailySummary.summarize(emptyList(), emptyList()))
    }

    @Test fun noCallLogPermission() {
        assertEquals(
            "No new messages. I can't read missed calls without the call log permission.",
            DailySummary.summarize(null, emptyList()),
        )
    }

    @Test fun messagesCallsAndAlerts() {
        val log = listOf(
            n("com.whatsapp", "WhatsApp", "Rahim"),
            n("com.whatsapp", "WhatsApp", "Karim (3 messages)"),
            n("com.whatsapp", "WhatsApp", "Rahim"),
            n("com.whatsapp", "WhatsApp", "WhatsApp"),
            n("org.telegram.messenger", "Telegram", "Mom"),
            n("com.android.dialer", "Phone", "Missed call"),
            n("com.bkash.customerapp", "bKash", "You received Tk 500"),
            n("com.daraz.android", "Daraz", "Order shipped"),
            n("com.daraz.android", "Daraz", "Sale", important = false),
            n("com.alrasin.jarvis", "Jarvis", "Listening"),
        )
        assertEquals(
            "Messages: WhatsApp from Rahim and Karim, Telegram from Mom. 2 missed calls: Ana twice. 1 other alert.",
            DailySummary.summarize(listOf("Ana", "Ana"), log),
        )
    }

    @Test fun manySendersAndApps() {
        val log = listOf("A", "B", "C", "D", "E").map { n("com.whatsapp", "WhatsApp", it) } + listOf(
            n("org.telegram.messenger", "Telegram", "X"),
            n("com.google.android.gm", "Gmail", "Y"),
            n("com.viber.voip", "Viber", "5 new messages"),
            n("com.discord", "Discord", "Z"),
        )
        assertEquals(
            "Messages: WhatsApp from E, D, C and 2 others, Discord from Z, Viber and 2 more apps.",
            DailySummary.summarize(emptyList(), log),
        )
    }
}

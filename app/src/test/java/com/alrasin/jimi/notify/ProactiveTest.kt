package com.alrasin.jimi.notify

import com.alrasin.jimi.data.NotificationLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProactiveTest {
    private val hour = 3_600_000L
    private val now = 100 * hour

    private fun row(pkg: String, app: String, title: String, text: String = "", important: Boolean = true) =
        NotificationLog(timeMs = now, pkg = pkg, appName = app, title = title, text = text, important = important, reason = "", status = "")

    @Test fun lateNightOnlyInWindowOncePerNightNotInBedtime() {
        assertEquals("Boss, it's 1:05 at night. Time to sleep?", Proactive.lateNight(now, 65, 0, quiet = false))
        assertEquals("Boss, it's 12:30 at night. Time to sleep?", Proactive.lateNight(now, 30, 0, quiet = false))
        assertNull(Proactive.lateNight(now, 29, 0, quiet = false))
        assertNull(Proactive.lateNight(now, 240, 0, quiet = false))
        assertNull(Proactive.lateNight(now, 23 * 60, 0, quiet = false))
        assertNull(Proactive.lateNight(now, 65, 0, quiet = true))
        assertNull(Proactive.lateNight(now, 65, now - hour, quiet = false))
        assertEquals("Boss, it's 2:00 at night. Time to sleep?", Proactive.lateNight(now, 120, now - 20 * hour, quiet = false))
    }

    @Test fun awaySummaryNeedsLongBreakAndSomeAlerts() {
        val log = listOf(
            row("com.whatsapp", "WhatsApp", "Sara", "hi"),
            row("com.example.mail", "Mail", "Boss", "Report"),
            row("com.bkash.customerapp", "bKash", "Cash in"),
            row("x", "X", "noise", important = false),
        )
        assertEquals("While you were away: Messages: WhatsApp from Sara. 1 other alert.", Proactive.awaySummary(4 * hour, log, false))
        assertNull(Proactive.awaySummary(2 * hour, log, false))
        assertNull(Proactive.awaySummary(4 * hour, log, true))
        assertNull(Proactive.awaySummary(4 * hour, log.drop(1), false))
    }
}

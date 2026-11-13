package com.alrasin.jarvis.notify

import com.alrasin.jarvis.brain.ContextModes
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.data.NotificationLog

/**
 * What Jimi says on its own (ROADMAP #44, B §K 8.4): a late-night nudge, important alerts read out, and a short
 * "while you were away" summary when the phone is unlocked after a long break. Bedtime (quiet hours) mutes all of it.
 * Pure so it is unit tested; the service and listener only feed it times and rows.
 */
object Proactive {
    /** Late-night nudge window: 00:30 to 04:00. */
    const val LATE_START = 30
    const val LATE_END = 4 * 60
    /** Unlock after this long away gets a summary. */
    const val AWAY_MS = 3 * 3_600_000L
    /** At least this many important alerts before a summary is worth saying. */
    const val MIN_UNREAD = 2
    /** Gap between two read-outs so a burst of alerts is not a speech. */
    const val READ_GAP_MS = 2 * 60_000L
    private const val MAX_TEXT = 120
    private const val NOTHING = "Nothing missed."

    /** Nudge once per night when the phone is unlocked late. [lastNudgeMs] is the last nudge time, 0 if never. */
    fun lateNight(nowMs: Long, minuteOfDay: Int, lastNudgeMs: Long, quiet: Boolean): String? {
        if (quiet || minuteOfDay !in LATE_START until LATE_END) return null
        if (nowMs - lastNudgeMs < LATE_END * 60_000L) return null   // already nudged tonight
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        return "${Persona.ADDRESS}, it's ${if (h == 0) 12 else h}:${m.toString().padStart(2, '0')} at night. Time to sleep?"
    }

    /** One line for an important alert, or null. Chat apps are skipped when car/headphones mode already reads them. */
    fun importantLine(row: NotificationLog, quiet: Boolean, chatsRead: Boolean, nowMs: Long, lastReadMs: Long): String? {
        if (quiet || !row.important || Triage.isMoneyApp(row.pkg)) return null
        if (chatsRead && Triage.isChatApp(row.pkg)) return null
        if (nowMs - lastReadMs < READ_GAP_MS) return null
        if (Triage.isChatApp(row.pkg)) {
            return ContextModes.messageLine(row.appName, row.title, row.text)?.let { "${Persona.ADDRESS}, $it" }
        }
        val title = row.title.trim()
        val text = row.text.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)
        if (title.isEmpty() && text.isEmpty()) return null
        val body = listOf(title, text).filter { it.isNotEmpty() }.distinct().joinToString(": ")
        return "${Persona.ADDRESS}, ${row.appName}: $body"
    }

    /** "While you were away: …" after [awayMs] off, from the important alerts logged in that time. */
    fun awaySummary(awayMs: Long, log: List<NotificationLog>, quiet: Boolean): String? {
        if (quiet || awayMs < AWAY_MS) return null
        val kept = log.filter { it.important && !Triage.isMoneyApp(it.pkg) }
        if (kept.size < MIN_UNREAD) return null
        val said = DailySummary.summarize(emptyList(), kept)
        return if (said == NOTHING) null else "While you were away: $said"
    }
}

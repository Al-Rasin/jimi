package com.alrasin.jarvis.notify

import android.content.Context
import com.alrasin.jarvis.actions.CallText
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.missedCallers
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.NotificationLog

/**
 * "What did I miss" (ROADMAP §I.4): who messaged in which app, missed calls, and how many other alerts.
 * Money apps are left out (D-072). Pure text part is unit tested.
 */
object DailySummary {
    const val WINDOW_MS = 12 * 3_600_000L
    private const val MAX_SENDERS = 3
    private const val MAX_APPS = 3

    /** Calls come from the call log, not from dialer notifications. */
    private val CALL_APPS = setOf("com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.contacts")
    private val COUNT_TAIL = Regex("\\s*\\(\\d+ (?:new )?messages?\\)$", RegexOption.IGNORE_CASE)
    private val COUNT_ONLY = Regex("^\\d+ (?:new )?messages?(?: from \\d+ chats?)?$", RegexOption.IGNORE_CASE)

    /** [missed] is null when the call log can't be read. [log] is the notification log of the window. */
    fun summarize(missed: List<String>?, log: List<NotificationLog>, ownPkg: String = "com.alrasin.jarvis"): String {
        val kept = log.filter { it.important && !it.pkg.startsWith(ownPkg) && !Triage.isMoneyApp(it.pkg) && it.pkg !in CALL_APPS }
        val (chats, others) = kept.partition { Triage.isChatApp(it.pkg) }
        val parts = ArrayList<String>()
        messages(chats)?.let(parts::add)
        if (!missed.isNullOrEmpty()) parts += CallText.missedSummary(missed).removeSuffix(".")
        if (others.isNotEmpty()) {
            val n = others.size
            parts += "$n other alert${if (n == 1) "" else "s"}"
        }
        if (parts.isEmpty()) return if (missed == null) "No new messages. I can't read missed calls without the call log permission." else "Nothing missed."
        return parts.joinToString(". ") + "."
    }

    /** "Messages: WhatsApp from Rahim and Karim, Telegram from Mom". */
    private fun messages(chats: List<NotificationLog>): String? {
        val byApp = chats.sortedByDescending { it.timeMs }.groupBy { it.appName }
        val apps = byApp.entries.sortedByDescending { it.value.size }.map { (app, items) ->
            val senders = items.mapNotNull { sender(it) }.distinct()
            if (senders.isEmpty()) app else "$app from ${names(senders)}"
        }
        if (apps.isEmpty()) return null
        val more = apps.size - MAX_APPS
        return "Messages: " + apps.take(MAX_APPS).joinToString(", ") + if (more > 0) " and $more more app${if (more == 1) "" else "s"}" else ""
    }

    private fun sender(n: NotificationLog): String? {
        val t = n.title.replace(COUNT_TAIL, "").trim()
        return t.takeUnless { it.isEmpty() || it.equals(n.appName, ignoreCase = true) || COUNT_ONLY.matches(it) }
    }

    private fun names(all: List<String>): String {
        val shown = all.take(MAX_SENDERS)
        val rest = all.size - shown.size
        return when {
            rest > 0 -> shown.joinToString(", ") + " and $rest other${if (rest == 1) "" else "s"}"
            shown.size == 1 -> shown[0]
            else -> shown.dropLast(1).joinToString(", ") + " and " + shown.last()
        }
    }
}

fun dailySummaryTool(context: Context, contacts: Contacts, dao: NotificationDao): Tool =
    SimpleTool("daily_summary", "What did I miss: messages by app, missed calls and other alerts", risk = Risk.VOICE_ID) { _, _ ->
        val since = System.currentTimeMillis() - DailySummary.WINDOW_MS
        ToolResult.ok(DailySummary.summarize(missedCallers(context, contacts, since), dao.since(since), context.packageName))
    }

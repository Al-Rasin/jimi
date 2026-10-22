package com.alrasin.jarvis.notify

import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.NotificationDao

fun notificationTools(dao: NotificationDao): List<Tool> {
    fun l() = JarvisNotificationListener.instance
    val off = ToolResult.fail("Notification access is off. Open Jarvis setup to turn it on.")
    return listOf(
        SimpleTool("read_notifications", "Read current notifications", risk = Risk.VOICE_ID) { _, _ ->
            val items = l()?.current() ?: return@SimpleTool off
            if (items.isEmpty()) return@SimpleTool ToolResult.ok("No notifications.")
            ToolResult.ok(
                "${items.size} notification${if (items.size > 1) "s" else ""}. " +
                    items.take(4).joinToString(". ") { "${it.app}: ${it.title}${if (it.text.isNotBlank()) ", ${it.text.take(80)}" else ""}" } + ".",
                items.joinToString("\n") { "• ${it.app} — ${it.title}: ${it.text}" },
            )
        },
        SimpleTool("notification_summary", "Summary of what came in lately", risk = Risk.VOICE_ID) { _, _ ->
            val since = System.currentTimeMillis() - 6 * 3_600_000L
            val log = dao.since(since)
            if (log.isEmpty()) return@SimpleTool ToolResult.ok("Nothing new in the last 6 hours.")
            val imp = log.filter { it.important }
            val cleared = log.count { it.status == "cleared" }
            val byApp = imp.groupBy { it.appName }.entries.sortedByDescending { it.value.size }
            val say = buildString {
                append("${imp.size} important from ${byApp.size} app${if (byApp.size == 1) "" else "s"}")
                if (byApp.isNotEmpty()) append(": " + byApp.take(3).joinToString(", ") { "${it.value.size} ${it.key}" })
                append(". ")
                imp.firstOrNull()?.let { append("Latest: ${it.appName}, ${it.title}. ") }
                if (cleared > 0) append("I requested clearing $cleared notifications; their text and reasons are in history.")
            }
            ToolResult.ok(say.trim(), imp.joinToString("\n") { "• ${it.appName} — ${it.title}: ${it.text}" })
        },
        SimpleTool("clear_notifications", "Clear all notifications", risk = Risk.CONFIRM) { _, _ ->
            val lst = l() ?: return@SimpleTool off
            clearResult(lst.clearAll(), restored = false)
        },
        SimpleTool("undo_notification_clear", "Undo last clear: restore the last cleared notifications as Jimi history copies", risk = Risk.VOICE_ID) { _, _ ->
            val lst = l() ?: return@SimpleTool off
            clearResult(lst.undoLastClear(), restored = true)
        },
        SimpleTool("explain_notification_clear", "Answer why did you clear that using the saved reason", risk = Risk.VOICE_ID) { _, _ ->
            val items = l()?.lastCleared()?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(dao.all().filter { it.status in setOf("cleared", "clear_pending", "restored") }.maxByOrNull { it.id })
            if (items.isEmpty()) return@SimpleTool ToolResult.ok("There is no saved clear to explain.")
            ToolResult.ok(
                items.take(3).joinToString(". ") { "${it.appName}: ${it.reason}" },
                items.joinToString("\n") { "${it.appName} — ${it.title}: ${it.reason} (${it.status})" },
            )
        },
        SimpleTool(
            "search_notification_history", "Search saved notifications, including cleared ones and their reasons",
            listOf(arg("query", "Words in app, title, text, reason or review label")), risk = Risk.VOICE_ID,
        ) { call, _ ->
            val query = call.arg("query") ?: return@SimpleTool ToolResult.fail("What should I search notification history for?")
            val items = NotificationHistorySearch.search(dao.all(), query)
            ToolResult.ok(
                if (items.isEmpty()) "No saved notifications match." else "${items.size} saved notifications match. " +
                    items.take(3).joinToString(". ") { "${it.appName}: ${it.title}" },
                items.joinToString("\n") { "[${it.id}] ${it.appName} — ${it.title}: ${it.text}\n${it.status}; ${it.reason}; review: ${it.label ?: "unreviewed"}" },
            )
        },
    )
}

private fun clearResult(result: ClearResult, restored: Boolean): ToolResult {
    val action = if (restored) "Restored ${result.completed} notifications as Jimi copies. Original app actions are not restored."
        else "Requested clearing ${result.completed} notifications. Saved copies and reasons remain in history."
    if (result.failed > 0 || result.errors.isNotEmpty()) {
        return ToolResult.fail("$action Some items could not be completed; check notification history and try again.")
    }
    if (result.completed == 0) return ToolResult.ok(if (restored) "There is no last clear to undo." else "No clearable notifications.")
    return ToolResult.ok(action)
}

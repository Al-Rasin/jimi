package com.alrasin.jarvis.notify

import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.NotificationDao

fun notificationTools(dao: NotificationDao): List<Tool> {
    fun l() = JarvisNotificationListener.instance
    val off = ToolResult.fail("Notification access is off. Open Jarvis setup to turn it on.")
    return listOf(
        SimpleTool("read_notifications", "Read current notifications") { _, _ ->
            val items = l()?.current() ?: return@SimpleTool off
            if (items.isEmpty()) return@SimpleTool ToolResult.ok("No notifications.")
            ToolResult.ok(
                "${items.size} notification${if (items.size > 1) "s" else ""}. " +
                    items.take(4).joinToString(". ") { "${it.app}: ${it.title}${if (it.text.isNotBlank()) ", ${it.text.take(80)}" else ""}" } + ".",
                items.joinToString("\n") { "• ${it.app} — ${it.title}: ${it.text}" },
            )
        },
        SimpleTool("notification_summary", "Summary of what came in lately") { _, _ ->
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
                if (cleared > 0) append("I cleared $cleared junk.")
            }
            ToolResult.ok(say.trim(), imp.joinToString("\n") { "• ${it.appName} — ${it.title}: ${it.text}" })
        },
        SimpleTool("clear_notifications", "Clear all notifications", risk = Risk.CONFIRM) { _, _ ->
            val lst = l() ?: return@SimpleTool off
            lst.clearAll()
            ToolResult.ok("Cleared.")
        },
    )
}

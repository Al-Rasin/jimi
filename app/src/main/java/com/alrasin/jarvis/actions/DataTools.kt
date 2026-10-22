package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.MemoryFact
import com.alrasin.jarvis.data.Note
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.Reminder
import com.alrasin.jarvis.data.ReminderDao
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notes, reminders (PLAN 6) and memory facts (PLAN 10). */
fun dataTools(notes: NoteDao, reminders: ReminderDao, scheduler: ReminderScheduler, memory: MemoryDao): List<Tool> {
    val zone = ZoneId.systemDefault()
    fun whenText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()
        val day = when (t.toLocalDate()) {
            LocalDate.now() -> "today"
            LocalDate.now().plusDays(1) -> "tomorrow"
            else -> t.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.US))
        }
        return "$day at ${speakTime(t.hour, t.minute)}"
    }
    return listOf(
        SimpleTool("create_note", "Save a note", listOf(arg("text", "note text"))) { c, _ ->
            val t = c.arg("text") ?: return@SimpleTool ToolResult.fail("What should I note?")
            notes.insert(Note(timeMs = System.currentTimeMillis(), text = t))
            ToolResult.ok("Noted.")
        },
        SimpleTool("read_notes", "Read the last notes") { _, _ ->
            val list = notes.last(5)
            if (list.isEmpty()) return@SimpleTool ToolResult.ok("You have no notes.")
            ToolResult.ok("Your last notes: " + list.joinToString(". ") { it.text } + ".", list.joinToString("\n") { "• ${it.text}" })
        },
        SimpleTool(
            "create_reminder", "Remind at a time",
            listOf(arg("text", "what to remind"), arg("time", "yyyy-MM-ddTHH:mm"), arg("repeat", "none, daily or weekly", false, listOf("none", "daily", "weekly"))),
        ) { c, _ ->
            val text = c.arg("text") ?: return@SimpleTool ToolResult.fail("Remind you about what?")
            val at = c.arg("time")?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }
                ?: return@SimpleTool ToolResult.fail("When should I remind you?")
            val ms = at.atZone(zone).toInstant().toEpochMilli()
            if (ms < System.currentTimeMillis() - 60_000) return@SimpleTool ToolResult.fail("That time has passed.")
            val r = Reminder(text = text, dueMs = ms, repeat = c.arg("repeat") ?: "none")
            scheduler.schedule(r.copy(id = reminders.insert(r)))
            val rep = when (r.repeat) { "daily" -> " every day"; "weekly" -> " every week"; else -> "" }
            ToolResult.ok("Okay, I'll remind you to $text ${whenText(ms)}$rep.")
        },
        SimpleTool("list_reminders", "List pending reminders") { _, _ ->
            val list = reminders.pending()
            if (list.isEmpty()) return@SimpleTool ToolResult.ok("No reminders.")
            ToolResult.ok(
                "${list.size} reminder${if (list.size > 1) "s" else ""}: " + list.take(5).joinToString(". ") { "${it.text}, ${whenText(it.dueMs)}" } + ".",
                list.joinToString("\n") { "• ${it.text} — ${whenText(it.dueMs)}" },
            )
        },
        SimpleTool("delete_reminder", "Delete a reminder", listOf(arg("text", "which reminder, or all"))) { c, _ ->
            val q = c.arg("text") ?: return@SimpleTool ToolResult.fail("Which reminder?")
            val list = reminders.pending()
            val del = if (q == "all") list else listOfNotNull(Fuzzy.best(q, list, { it.text }, min = 0.5))
            if (del.isEmpty()) return@SimpleTool ToolResult.fail("I can't find that reminder.")
            del.forEach { scheduler.cancel(it.id); reminders.delete(it.id) }
            ToolResult.ok(if (del.size == 1) "Deleted the reminder ${del[0].text}." else "Deleted ${del.size} reminders.")
        },
        SimpleTool("remember_fact", "Remember a fact about the user", listOf(arg("text", "the fact"))) { c, _ ->
            val t = c.arg("text") ?: return@SimpleTool ToolResult.fail("Remember what?")
            memory.insert(MemoryFact(timeMs = System.currentTimeMillis(), text = t))
            ToolResult.ok("I'll remember that.")
        },
        SimpleTool("forget_fact", "Forget a remembered fact", listOf(arg("text", "which fact, or all"))) { c, _ ->
            val q = c.arg("text") ?: return@SimpleTool ToolResult.fail("Forget what?")
            val all = memory.all()
            val del = if (q == "all" || q == "everything") all else listOfNotNull(Fuzzy.best(q, all, { it.text }, min = 0.4))
            if (del.isEmpty()) return@SimpleTool ToolResult.fail("I don't remember that.")
            del.forEach { memory.delete(it.id) }
            ToolResult.ok("Forgotten.")
        },
        SimpleTool("recall_facts", "What Jarvis remembers") { _, _ ->
            val all = memory.all()
            if (all.isEmpty()) ToolResult.ok("I don't remember anything yet.")
            else ToolResult.ok("I remember: " + all.take(5).joinToString(". ") { it.text } + ".", all.joinToString("\n") { "• ${it.text}" })
        },
    )
}

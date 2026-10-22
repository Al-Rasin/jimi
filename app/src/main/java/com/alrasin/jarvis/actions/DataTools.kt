package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.MemoryRetriever
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.MemoryFact
import com.alrasin.jarvis.data.Note
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.Reminder
import com.alrasin.jarvis.data.ReminderDao
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Notes, reminders (PLAN 6) and memory facts (PLAN 10). */
fun dataTools(
    notes: NoteDao,
    reminders: ReminderDao,
    scheduler: ReminderScheduler,
    memory: MemoryDao,
    selectedFactId: () -> Long? = { null },
): List<Tool> {
    fun whenText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val day = t.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.US))
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
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
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
        SimpleTool("forget_fact", "Forget a remembered fact", listOf(arg("text", "which fact"))) { c, _ ->
            val q = c.arg("text") ?: return@SimpleTool ToolResult.fail("Forget what?")
            val candidate = MemoryRetriever.selectForgetting(
                q,
                memory.all().map { MemoryRetriever.Memory(it.id, it.text, it.timeMs) },
                selectedFactId(),
            ) ?: return@SimpleTool ToolResult.fail("Which remembered fact should I forget?")
            if (candidate.requiresConfirmation) {
                return@SimpleTool ToolResult(
                    false,
                    "Forget '${candidate.memory.text}'?",
                    Json.write(mapOf(
                        "type" to "forget_fact_confirmation",
                        "id" to candidate.memory.id,
                        "text" to candidate.memory.text,
                    )),
                )
            }
            memory.delete(candidate.memory.id)
            ToolResult.ok("Forgotten.")
        },
        SimpleTool("recall_facts", "What Jarvis remembers") { _, _ ->
            val all = memory.all()
            if (all.isEmpty()) ToolResult.ok("I don't remember anything yet.")
            else ToolResult.ok("I remember: " + all.take(5).joinToString(". ") { it.text } + ".", all.joinToString("\n") { "• ${it.text}" })
        },
    )
}

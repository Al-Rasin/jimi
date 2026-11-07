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
    noteApp: NoteApp? = null,
    selectedFactId: () -> Long? = { null },
): List<Tool> {
    fun whenText(ms: Long): String {
        val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val day = t.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.US))
        return "$day at ${speakTime(t.hour, t.minute)}"
    }
    var lastFactId: Long? = null
    return listOf(
        SimpleTool("create_note", "Save a note", listOf(arg("text", "note text"))) { c, _ ->
            val t = c.require("text", "What should I note?")
            notes.insert(Note(timeMs = System.currentTimeMillis(), text = t))   // kept for "read my notes"
            val app = noteApp?.save(t)
            when {
                app != null -> ToolResult.ok("Saved in $app.")
                noteApp?.target() == null -> ToolResult.ok("Noted.")
                else -> ToolResult.ok("I couldn't open your notes app, so I saved it in Jimi.")
            }
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
            val text = c.require("text", "Remind you about what?")
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
            val q = c.require("text", "Which reminder?")
            val list = reminders.pending()
            val del = if (q == "all") list else listOfNotNull(Fuzzy.best(q, list, { it.text }, min = 0.5))
            if (del.isEmpty()) return@SimpleTool ToolResult.fail("I can't find that reminder.")
            del.forEach { scheduler.cancel(it.id); reminders.delete(it.id) }
            ToolResult.ok(if (del.size == 1) "Deleted the reminder ${del[0].text}." else "Deleted ${del.size} reminders.")
        },
        SimpleTool("remember_fact", "Remember a fact about the user", listOf(arg("text", "the fact"))) { c, _ ->
            val t = c.arg("text")?.let(FactText::clean) ?: return@SimpleTool ToolResult.fail("Remember what?")
            val all = memory.all()
            all.firstOrNull { FactText.key(it.text) == FactText.key(t) }?.let {
                lastFactId = it.id
                return@SimpleTool ToolResult.ok("I already know that.")
            }
            // Same subject ("my car is …") = the new fact replaces the old one instead of a wrong duplicate.
            val old = FactText.subject(t)?.let { s -> all.filter { FactText.subject(it.text) == s } }.orEmpty()
            old.forEach { memory.delete(it.id) }
            lastFactId = memory.insert(MemoryFact(timeMs = System.currentTimeMillis(), text = t))
            ToolResult.ok(if (old.isEmpty()) "I'll remember that." else "Updated. $t.")
        },
        SimpleTool("forget_fact", "Forget a remembered fact", listOf(arg("text", "which fact"))) { c, _ ->
            val q = c.require("text", "Forget what?")
            val candidate = MemoryRetriever.selectForgetting(
                q,
                memory.all().map { MemoryRetriever.Memory(it.id, it.text, it.timeMs) },
                selectedFactId() ?: lastFactId,
            ) ?: return@SimpleTool ToolResult.fail("Which remembered fact should I forget?")
            // The security gate already asked "Forget …?" (forget_fact is CONFIRM), so a second question would never be answered.
            memory.delete(candidate.memory.id)
            if (lastFactId == candidate.memory.id) lastFactId = null
            ToolResult.ok("Forgotten: ${candidate.memory.text}.", Json.write(mapOf("id" to candidate.memory.id, "text" to candidate.memory.text)))
        },
        SimpleTool("recall_facts", "What Jarvis remembers") { _, _ ->
            val all = tidyFacts(memory)
            lastFactId = all.singleOrNull()?.id
            if (all.isEmpty()) ToolResult.ok("I don't remember anything yet.")
            else ToolResult.ok("I remember: " + all.take(5).joinToString(". ") { it.text } + ".", all.joinToString("\n") { "• ${it.text}" })
        },
    )
}

/** Cleans stored facts: drops junk and older exact duplicates, rewrites facts with timestamps. Newest first. */
private suspend fun tidyFacts(memory: MemoryDao): List<MemoryFact> {
    val seen = HashSet<String>()
    val out = ArrayList<MemoryFact>()
    for (f in memory.all().sortedByDescending { it.timeMs }) {
        val clean = FactText.clean(f.text)
        if (clean == null || !seen.add(FactText.key(clean))) {
            memory.delete(f.id)
            continue
        }
        if (clean == f.text) out.add(f)
        else {
            memory.delete(f.id)
            val fixed = f.copy(id = 0, text = clean)
            out.add(fixed.copy(id = memory.insert(fixed)))
        }
    }
    return out
}

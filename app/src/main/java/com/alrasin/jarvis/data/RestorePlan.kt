package com.alrasin.jarvis.data

import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.asObj

/**
 * Restore (ROADMAP #46, B §K 15.3): reads a file made by [Backup.export] and works out which rows are new.
 * Merges, never replaces: rows already on the phone are skipped, so restoring the same file twice adds nothing.
 * Logs (turns, tool calls, notifications) are training data, not restored. Pure so it is unit tested.
 */
class RestorePlan(
    val notes: List<Note>,
    val reminders: List<Reminder>,
    val facts: List<MemoryFact>,
    val rules: List<AppRule>,
    val routines: List<Routine>,
) {
    val total get() = notes.size + reminders.size + facts.size + rules.size + routines.size

    fun summary(): String = if (total == 0) "Nothing new to restore." else listOf(
        notes.size to "note", reminders.size to "reminder", facts.size to "fact", rules.size to "app rule", routines.size to "routine",
    ).filter { it.first > 0 }.joinToString(", ", prefix = "Restored ") { "${it.first} ${it.second}${if (it.first == 1) "" else "s"}" } + "."

    /** What is on the phone now, for duplicate checks. */
    class Current(
        val notes: List<Note> = emptyList(),
        val reminders: List<Reminder> = emptyList(),
        val facts: List<MemoryFact> = emptyList(),
        val rules: List<AppRule> = emptyList(),
        val routines: List<Routine> = emptyList(),
    )

    companion object {
        private val RULE_MODES = setOf("keep", "clear")
        private val REPEATS = setOf("none", "daily", "weekly")

        /** Throws [IllegalArgumentException] when the text is not a Jarvis export. */
        fun from(json: String, now: Current, nowMs: Long = System.currentTimeMillis()): RestorePlan {
            val root = runCatching { Json.parse(json).asObj() }.getOrNull()
            require(root != null && root["version"] is Number) { "Not a Jarvis backup file" }

            val haveNotes = now.notes.map { it.timeMs to it.text }.toMutableSet()
            val notes = rows(root, "note").mapNotNull { m ->
                val text = m.text("text") ?: return@mapNotNull null
                val time = m.long("time") ?: nowMs
                if (haveNotes.add(time to text)) Note(timeMs = time, text = text) else null
            }

            val haveFacts = now.facts.map { it.text.trim().lowercase() }.toMutableSet()
            val facts = rows(root, "memory_fact").mapNotNull { m ->
                val text = m.text("text") ?: return@mapNotNull null
                if (haveFacts.add(text.lowercase())) MemoryFact(timeMs = m.long("time") ?: nowMs, text = text) else null
            }

            val haveReminders = now.reminders.filter { !it.done }.map { it.text.trim().lowercase() to it.dueMs }.toMutableSet()
            val reminders = rows(root, "reminder").mapNotNull { m ->
                val text = m.text("text") ?: return@mapNotNull null
                val repeat = (m["repeat"] as? String)?.takeIf { it in REPEATS } ?: "none"
                var r = Reminder(text = text, dueMs = m.long("due") ?: return@mapNotNull null, repeat = repeat)
                if (r.dueMs <= nowMs) {
                    // Past one-time reminders are dropped; repeating ones move to their next time.
                    r = r.copy(dueMs = ReminderScheduler.next(r, nowMs) ?: return@mapNotNull null)
                }
                if (haveReminders.add(text.lowercase() to r.dueMs)) r else null
            }

            val haveRules = now.rules.map { it.pkg }.toMutableSet()
            val rules = rows(root, "app_rule").mapNotNull { m ->
                val pkg = m.text("pkg") ?: return@mapNotNull null
                val mode = (m["mode"] as? String)?.takeIf { it in RULE_MODES } ?: return@mapNotNull null
                if (haveRules.add(pkg)) AppRule(pkg, mode) else null
            }

            val haveRoutines = now.routines.map { it.name }.toMutableSet()
            val routines = rows(root, "routine").mapNotNull { m ->
                val name = m.text("name") ?: return@mapNotNull null
                val steps = m["steps"] as? List<*> ?: return@mapNotNull null
                if (haveRoutines.add(name)) Routine(name, Json.write(steps), m["enabled"] as? Boolean ?: true) else null
            }
            return RestorePlan(notes, reminders, facts, rules, routines)
        }

        private fun rows(root: Map<*, *>, key: String): List<Map<*, *>> =
            (root[key] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }

        private fun Map<*, *>.text(key: String): String? = (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        private fun Map<*, *>.long(key: String): Long? = (this[key] as? Number)?.toLong()
    }
}

package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * An alarm, timer or reminder that is missing its time or text ("set an alarm", "remind me to call mom").
 * Jimi asks once for the missing part; an answer that still has no time ends it (no loop, ROADMAP §M.5).
 */
class TimeSlot(private val timeoutMs: Long = 60_000) {
    sealed interface Answer {
        data class Ready(val call: ToolCall) : Answer
        data class Ask(val question: String) : Answer
        data object Cancelled : Answer
        data object GaveUp : Answer
        data object None : Answer
    }

    private var call: ToolCall? = null
    private var at = 0L

    /** Starts a slot if [c] misses its time or text; returns the question to ask, or null. */
    fun start(c: ToolCall, now: Long): String? {
        val q = question(c) ?: return null
        call = c
        at = now
        return q
    }

    fun clear() { call = null }

    /** [newCommand]: the reply matched another rule; it wins when the reply does not fill the slot. */
    fun answer(reply: String, now: Long, newCommand: Boolean, time: LocalDateTime = LocalDateTime.now()): Answer {
        val c = call ?: return Answer.None
        if (now - at !in 0..timeoutMs) { clear(); return Answer.None }
        val words = reply.trim().trimEnd('.', '!', '?').lowercase()
        if (Persona.isNo(words) || CANCEL.matches(words)) { clear(); return Answer.Cancelled }
        val next = fill(c, words, time)
        if (next == null) {
            clear()
            return if (newCommand) Answer.None else Answer.GaveUp
        }
        at = now
        val q = question(next)
        return if (q == null) { clear(); Answer.Ready(next) } else { call = next; Answer.Ask(q) }
    }

    companion object {
        const val GAVE_UP = "I still didn't get a time, so I didn't set it."
        private val CANCEL = Regex("^(?:cancel|stop|never ?mind|forget it|leave it)$")
        private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")

        fun question(c: ToolCall): String? = when (c.tool) {
            "set_alarm" -> if (c.arg("time") == null) "For what time?" else null
            "set_timer" -> if (c.arg("seconds") == null) "For how long?" else null
            "create_reminder" -> when {
                c.arg("text") == null -> "Remind you about what?"
                c.arg("time") == null -> "When should I remind you?"
                else -> null
            }
            else -> null
        }

        private val REMIND = Regex("\\bremind(?:er)?\\b", RegexOption.IGNORE_CASE)

        /** The model picked set_alarm for "remind me …": reminder text must never become an alarm (ROADMAP §M.5). */
        fun asReminder(c: ToolCall, heard: String, now: LocalDateTime): ToolCall {
            if (c.tool != "set_alarm" || !REMIND.containsMatchIn(heard)) return c
            val text = c.arg("label") ?: RuleMatcher.match(heard, now)?.takeIf { it.tool == "create_reminder" }?.arg("text")
            val clock = c.arg("time")?.let { t -> runCatching { java.time.LocalTime.parse(t.substringAfter('T').padStart(5, '0')) }.getOrNull() }
            val date = c.arg("date")?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                ?: c.arg("time")?.takeIf { 'T' in it }?.let { runCatching { java.time.LocalDate.parse(it.substringBefore('T')) }.getOrNull() }
            val at = clock?.let { t ->
                val d = date ?: now.toLocalDate()
                d.atTime(t).let { if (date == null && !it.isAfter(now)) it.plusDays(1) else it }
            }
            return ToolCall("create_reminder", listOfNotNull(text?.let { "text" to it }, at?.let { "time" to it.format(ISO) }).toMap())
        }

        /** [c] with the missing part taken from [words], or null when the answer has no time. */
        internal fun fill(c: ToolCall, words: String, now: LocalDateTime): ToolCall? {
            // "five minutes" answers "For what time?" as "in five minutes".
            val spoken = if (TimeParser.durationOnly(words) != null) "in $words" else words
            return when (c.tool) {
                "set_alarm" -> RuleMatcher.match("set alarm $spoken", now)
                    ?.takeIf { it.tool == "set_alarm" && it.arg("time") != null }
                    ?.let { c.copy(args = it.args + c.args.filterKeys { k -> k == "label" }) }
                "set_timer" -> TimeParser.duration(words)?.takeIf { it > 0 }?.let { c.copy(args = c.args + ("seconds" to it.toString())) }
                "create_reminder" -> if (c.arg("text") == null) {
                    val heard = RuleMatcher.match("remind me to $words", now)?.takeIf { it.tool == "create_reminder" }
                    val text = heard?.arg("text") ?: words
                    c.copy(args = c.args + ("text" to text) + (heard?.args.orEmpty() - "text"))
                } else {
                    TimeParser.parse(spoken, now)?.takeIf { it.hasTime && it.at.isAfter(now) }
                        ?.let { c.copy(args = c.args + ("time" to it.at.format(ISO))) }
                }
                else -> null
            }
        }
    }
}

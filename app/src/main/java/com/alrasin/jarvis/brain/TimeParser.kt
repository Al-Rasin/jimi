package com.alrasin.jarvis.brain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Natural time in commands (PLAN 6.3): "at 7 30 pm", "tomorrow 5 pm", "in 2 hours", "every friday at 6",
 * "half past nine", "noon". Input: normalized text with numbers as digits (see [NumberWords]).
 */
object TimeParser {
    data class Match(
        val at: LocalDateTime,
        /** none | daily | weekly */
        val repeat: String,
        /** [text] with the time words removed, e.g. "remind me to call mom". */
        val rest: String,
        val hasTime: Boolean,
    )

    private val DAYS = mapOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY,
    )
    private const val DAY_RE = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private val AMPM = "(a ?m|p ?m)"

    private val IN_DURATION = Regex("\\b(?:in|after) ((?:(?:\\d+|a|an|half an?|one and a half)\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\s*(?:and\\s*)?)+)")

    /** Seconds in "2 hours and 5 minutes", "half an hour", "90 seconds". Null if none. */
    fun duration(text: String): Long? {
        var total = 0L
        var found = false
        val re = Regex("(\\d+|a|an|half an?|one and a half)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b")
        for (m in re.findAll(text)) {
            val n = when (val q = m.groupValues[1]) {
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                "one and a half" -> 1.5
                else -> q.toDouble()
            }
            val unit = m.groupValues[2]
            total += (n * when {
                unit.startsWith("s") -> 1
                unit.startsWith("m") -> 60
                unit.startsWith("h") -> 3600
                else -> 86400
            }).toLong()
            found = true
        }
        if (!found && Regex("\\bhalf an? hour\\b").containsMatchIn(text)) return 1800
        return if (found) total else null
    }

    fun parse(text: String, now: LocalDateTime): Match? {
        var rest = " $text "
        // 1. relative: "in 2 hours"
        IN_DURATION.find(rest)?.let { m ->
            val secs = duration(m.groupValues[1]) ?: return@let
            return Match(now.plusSeconds(secs).withNano(0), "none", clean(rest.replaceRange(m.range, " ")), true)
        }
        // 2. day part
        var date: LocalDate? = null
        var repeat = "none"
        var weekday: DayOfWeek? = null
        var hint: String? = null   // am | pm
        fun cut(re: Regex): MatchResult? = re.find(rest)?.also { rest = " " + rest.replaceRange(it.range, " ").trim() + " " }

        cut(Regex(" (?:every day|everyday|daily|each day)(?: at)? "))?.let { repeat = "daily" }
        cut(Regex(" (?:every|each) ($DAY_RE)s?(?: at)? "))?.let { repeat = "weekly"; weekday = DAYS[it.groupValues[1]] }
        if (weekday == null) cut(Regex(" (?:on |this |next )?($DAY_RE)(?: at)? "))?.let { weekday = DAYS[it.groupValues[1]] }
        cut(Regex(" (?:tomorrow|tmrw)(?: (morning|afternoon|evening|night))?(?: at)? "))?.let {
            date = now.toLocalDate().plusDays(1)
            hint = partHint(it.groupValues[1])
        }
        cut(Regex(" (?:today|tonight)(?: at)? "))?.let {
            date = now.toLocalDate()
            if (it.value.contains("tonight")) hint = "pm"
        }
        cut(Regex(" (?:this |in the )(morning|afternoon|evening)(?: at)? "))?.let { hint = partHint(it.groupValues[1]) }
        cut(Regex(" at night "))?.let { hint = "pm" }

        // 3. time of day
        var time: LocalTime? = null
        val t = cut(Regex(" (?:at |by |for )?(noon|midday|midnight) "))
        if (t != null) {
            time = if (t.groupValues[1] == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON
            hint = "fixed"
        }
        if (time == null) cut(Regex(" (?:at |by |for )?(half past|quarter past|quarter to) (\\d{1,2})(?: $AMPM)? "))?.let {
            val h = it.groupValues[2].toInt()
            hint = ampm(it.groupValues[3]) ?: hint
            time = when (it.groupValues[1]) {
                "half past" -> LocalTime.of(h % 24, 30)
                "quarter past" -> LocalTime.of(h % 24, 15)
                else -> LocalTime.of((h + 23) % 24, 45)
            }
        }
        if (time == null) {
            val m = Regex(" (?:at |by |for )?(\\d{1,2})(?: (\\d{2}))?(?: o clock)?(?: $AMPM)?(?: (in the morning|in the evening|in the afternoon|at night))? ")
                .findAll(rest).firstOrNull { m ->
                    // a bare number is only a time with "at"/am/pm/o'clock/minutes, or when a day word was given
                    val v = m.value
                    v.startsWith(" at ") || v.startsWith(" by ") || m.groupValues[2].isNotEmpty() || m.groupValues[3].isNotEmpty() ||
                        v.contains("o clock") || m.groupValues[4].isNotEmpty() || date != null || weekday != null || repeat != "none"
                }
            if (m != null) {
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h > 23 || mi > 59) return null
                hint = ampm(m.groupValues[3]) ?: partHint(m.groupValues[4].substringAfterLast(' ')) ?: hint
                time = LocalTime.of(h, mi)
                rest = " " + rest.replaceRange(m.range, " ").trim() + " "
            }
        }

        if (time == null && date == null && weekday == null && repeat == "none") return null
        var tod = time ?: LocalTime.of(9, 0)
        if (time != null && hint != "fixed" && tod.hour in 1..12) {
            tod = when (hint) {
                "pm" -> if (tod.hour < 12) tod.plusHours(12) else tod
                "am" -> if (tod.hour == 12) tod.minusHours(12) else tod
                else -> tod
            }
        }
        val today = now.toLocalDate()
        var at: LocalDateTime = when {
            date != null -> LocalDateTime.of(date, tod)
            weekday != null -> {
                var d = today
                while (d.dayOfWeek != weekday) d = d.plusDays(1)
                LocalDateTime.of(d, tod).let { if (!it.isAfter(now)) it.plusWeeks(1) else it }
            }
            else -> LocalDateTime.of(today, tod)
        }
        // no am/pm said and time already passed today: try the pm version, else tomorrow
        if (date == null && weekday == null && !at.isAfter(now)) {
            at = if (hint == null && time != null && time!!.hour in 1..11 && at.plusHours(12).isAfter(now)) at.plusHours(12)
            else at.plusDays(1)
        }
        return Match(at, repeat, clean(rest), time != null)
    }

    private fun ampm(s: String) = when (s.replace(" ", "")) { "am" -> "am"; "pm" -> "pm"; else -> null }

    private fun partHint(part: String?) = when (part) {
        "morning" -> "am"
        "afternoon", "evening", "night" -> "pm"
        else -> null
    }

    private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.TextNorm
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

// Regexes built once, not on every call (ROADMAP §L.4).
private val DURATION_ONLY_RE = Regex("\\bhalf an? hour\\b")
private val DURATION_ONLY_RE_2 = Regex("\\b(?:and|a half|for|of)\\b")
private val PARSE_RE = Regex(" (?:every day|everyday|daily|each day)(?: at)? ")
private val PARSE_RE_2 = Regex(" on (?:the )?(\\d{1,2})(?:st|nd|rd|th)?(?: at)? ")
private val PARSE_RE_3 = Regex(" (?:tomorrow|tmrw)(?: (morning|afternoon|evening|night))?(?: at)? ")
private val PARSE_RE_4 = Regex(" (?:today|tonight)(?: at)? ")
private val PARSE_RE_5 = Regex(" (?:this |in the )(morning|afternoon|evening)(?: at)? ")
private val PARSE_RE_6 = Regex(" at night ")
private val PARSE_RE_7 = Regex(" (?:at |by |for )?(noon|midday|midnight) ")
private val NORMALIZE_RE = Regex("\\b([ap])\\.m\\.?(?=\\s|$)")
private val NORMALIZE_RE_2 = Regex("(?<=\\d)([ap]m)\\b")
private val NORMALIZE_RE_3 = Regex("\\bon (the )?(first|second|third|fourth|fifth|sixth|seventh|eighth|ninth|tenth)\\b")
private val NORMALIZE_RE_5 = Regex("\\b(\\d{1,2}) 0 ([0-9])\\b")

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

    private val IN_DURATION = Regex("\\b(?:in|after) ((?:(?:\\d+|a|an|half an?|1 and a half)\\s*(?:seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\s*(?:and\\s*)?)+)")

    private val DURATION = Regex("(\\d+|a|an|half an?|1 and a half)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|days?)\\b")

    /** Seconds when the text is only a duration ("7 minutes", "an hour and a half"). Null if other words remain. */
    fun durationOnly(text: String): Long? {
        val n = normalize(text)
        val rest = DURATION.replace(n.replace(DURATION_ONLY_RE, " "), " ")
            .replace(DURATION_ONLY_RE_2, " ").trim()
        return if (rest.isEmpty()) duration(text)?.takeIf { it > 0 } else null
    }

    /** Seconds in "2 hours and 5 minutes", "half an hour", "90 seconds". Null if none. */
    fun duration(text: String): Long? {
        var total = 0L
        var found = false
        for (m in DURATION.findAll(normalize(text))) {
            val n = when (val q = m.groupValues[1]) {
                "a", "an" -> 1.0
                "half a", "half an" -> 0.5
                "1 and a half" -> 1.5
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
        if (!found && DURATION_ONLY_RE.containsMatchIn(text)) return 1800
        return if (found) total else null
    }

    fun parse(text: String, now: LocalDateTime): Match? {
        var rest = " ${normalize(text)} "
        // 1. relative: "in 2 hours"
        IN_DURATION.find(rest)?.let { m ->
            val secs = duration(m.groupValues[1]) ?: return@let
            return Match(now.plusSeconds(secs).withNano(0), "none", clean(rest.replaceRange(m.range, " ")), true)
        }
        // 2. day part
        var date: LocalDate? = null
        var repeat = "none"
        var weekday: DayOfWeek? = null
        var weekQualifier = ""
        var monthDay: Int? = null
        var part: String? = null
        var todayExplicit = false
        var hint: String? = null   // am | pm
        fun cut(re: Regex): MatchResult? = re.find(rest)?.also { rest = " " + rest.replaceRange(it.range, " ").trim() + " " }

        cut(PARSE_RE)?.let { repeat = "daily" }
        cut(Regex(" (?:every|each) ($DAY_RE)s?(?: at)? "))?.let { repeat = "weekly"; weekday = DAYS[it.groupValues[1]] }
        if (weekday == null) cut(Regex(" (?:on )?(?:(this|next) )?($DAY_RE)(?: at)? "))?.let {
            weekQualifier = it.groupValues[1]
            weekday = DAYS[it.groupValues[2]]
        }
        cut(PARSE_RE_2)?.let {
            monthDay = it.groupValues[1].toInt()
            if (monthDay !in 1..31) return null
        }
        cut(PARSE_RE_3)?.let {
            // Still awake after midnight: "tomorrow 7 am" means the coming morning.
            date = now.toLocalDate().plusDays(if (now.hour < 4) 0 else 1)
            part = it.groupValues[1].takeIf { value -> value.isNotEmpty() }
            hint = partHint(part)
        }
        cut(PARSE_RE_4)?.let {
            date = now.toLocalDate()
            todayExplicit = true
            if (it.value.contains("tonight")) { hint = "pm"; part = "night" }
        }
        cut(PARSE_RE_5)?.let { part = it.groupValues[1]; hint = partHint(part) }
        cut(PARSE_RE_6)?.let { hint = "pm" }

        // 3. time of day
        var time: LocalTime? = null
        val t = cut(PARSE_RE_7)
        if (t != null) {
            time = if (t.groupValues[1] == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON
            hint = "fixed"
        }
        if (time == null) cut(Regex(" (?:at |by |for )?(half past|quarter past|quarter to) (\\d{1,2})(?: $AMPM)? "))?.let {
            val h = it.groupValues[2].toInt()
            val explicitHint = ampm(it.groupValues[3])
            if (h > 23 || (explicitHint != null && h !in 1..12)) return null
            hint = explicitHint ?: hint
            val hour = if (h in 1..12) when (hint) {
                "pm" -> h % 12 + 12
                "am" -> h % 12
                else -> h
            } else h
            time = when (it.groupValues[1]) {
                "half past" -> LocalTime.of(hour, 30)
                "quarter past" -> LocalTime.of(hour, 15)
                else -> LocalTime.of(hour, 0).minusMinutes(15)
            }
            if (hint != null) hint = "fixed"
        }
        if (time == null) {
            val m = Regex(" (?:at |by |for )?(\\d{1,2})(?:[: ](\\d{2}))?(?: o clock)?(?: $AMPM)?(?: (in the morning|in the evening|in the afternoon|at night))? ")
                .findAll(rest).firstOrNull { m ->
                    // a bare number is only a time with "at"/am/pm/o'clock/minutes, or when a day word was given
                    val v = m.value
                    v.startsWith(" at ") || v.startsWith(" by ") || m.groupValues[2].isNotEmpty() || m.groupValues[3].isNotEmpty() ||
                        v.contains("o clock") || m.groupValues[4].isNotEmpty() || date != null || weekday != null || monthDay != null || hint != null || repeat != "none"
                }
            if (m != null) {
                val h = m.groupValues[1].toInt()
                val mi = m.groupValues[2].ifEmpty { "0" }.toInt()
                if (h > 23 || mi > 59 || (ampm(m.groupValues[3]) != null && h !in 1..12)) return null
                hint = ampm(m.groupValues[3]) ?: partHint(m.groupValues[4].substringAfterLast(' ')) ?: hint
                if (hint == null && (m.groupValues[1].startsWith("0") || m.value.contains(':') || h > 12)) hint = "fixed"
                time = LocalTime.of(h, mi)
                rest = " " + rest.replaceRange(m.range, " ").trim() + " "
            }
        }

        if (time == null && date == null && weekday == null && monthDay == null && part == null && repeat == "none") return null
        var tod = time ?: LocalTime.of(when (part) { "afternoon" -> 15; "evening" -> 18; "night" -> 21; else -> 9 }, 0)
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
                val d = when (weekQualifier) {
                    "this", "next" -> today.minusDays((today.dayOfWeek.value - 1).toLong())
                        .plusDays((weekday!!.value - 1).toLong())
                        .plusWeeks(if (weekQualifier == "next") 1 else 0)
                    else -> today.plusDays(((weekday!!.value - today.dayOfWeek.value + 7) % 7).toLong())
                }
                LocalDateTime.of(d, tod).let {
                    if (weekQualifier == "this" && !it.isAfter(now)) return null
                    if (!it.isAfter(now)) it.plusWeeks(1) else it
                }
            }
            monthDay != null -> {
                var month = today.withDayOfMonth(1)
                while (monthDay!! > month.lengthOfMonth() ||
                    !LocalDateTime.of(month.withDayOfMonth(monthDay!!.coerceAtMost(month.lengthOfMonth())), tod).isAfter(now)) {
                    month = month.plusMonths(1)
                }
                LocalDateTime.of(month.withDayOfMonth(monthDay!!), tod)
            }
            else -> LocalDateTime.of(today, tod)
        }
        // no am/pm said and time already passed today: try the pm version, else tomorrow
        if ((date == null || todayExplicit) && weekday == null && monthDay == null && !at.isAfter(now)) {
            at = if (!todayExplicit && hint == null && !text.contains(':') && time != null && time!!.hour in 1..11 && at.plusHours(12).isAfter(now)) at.plusHours(12)
            else at.plusDays(1)
        }
        return Match(at, repeat, clean(rest), time != null || part != null)
    }

    private fun normalize(text: String): String = NumberWords.normalize(
        text.lowercase(Locale.ENGLISH).replace("o'clock", "o clock")
            .replace(NORMALIZE_RE, "$1m")
            .replace(NORMALIZE_RE_2, " $1")
            .replace(NORMALIZE_RE_3) {
                "on ${it.groupValues[1]}${listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth").indexOf(it.groupValues[2]) + 1}th"
            }
            .replace(TextNorm.SPACES, " ").trim(),
    ).replace(NORMALIZE_RE_5, "$1 0$2")

    private fun ampm(s: String) = when (s.replace(" ", "")) { "am" -> "am"; "pm" -> "pm"; else -> null }

    private fun partHint(part: String?) = when (part) {
        "morning" -> "am"
        "afternoon", "evening", "night" -> "pm"
        else -> null
    }

    private fun clean(s: String) = s.replace(TextNorm.SPACES, " ").trim()
}

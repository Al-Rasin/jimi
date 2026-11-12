package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.TextNorm
import com.alrasin.jarvis.core.ToolCall
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/** RuleMatcher alarms, timers, notes, reminders and calendar. */
internal object TimeRules {
    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
    private val HM = DateTimeFormatter.ofPattern("HH:mm")

    private fun call(tool: String, vararg args: Pair<String, String?>) = RuleMatcher.call(tool, *args)

    private fun re(p: String) = Regex("^(?:$p)$")

    fun time(t: String, now: LocalDateTime): ToolCall? {
        // timer
        val timer = TIME_RE.find(t)
            ?: TIME_RE_2.find(t)
        timer?.let { m -> TimeParser.duration(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) } }
        if (t.matches(TIME_WHOLE_RE)) return call("set_timer")
        // "7 minutes" alone is a timer.
        TIME_RE_3.find(t)?.let { m ->
            TimeParser.durationOnly(m.groupValues[1])?.let { return call("set_timer", "seconds" to it.toString()) }
        }
        val all = "(?:the |my |all )*(?:alarms?|timers?)"
        if (t.matches(re("(?:cancel|stop|delete|turn off|switch off|dismiss|remove|clear) $all(?: and $all)?"))) return call("cancel_alarm")
        if (t.matches(TIME_WHOLE_RE_2)) return call("set_alarm")
        oneAlarm(t, now)?.let { return it }
        // alarm
        val alarm = TIME_RE_4.find(t)
            ?: TIME_RE_5.find(t)
            ?: TIME_RE_6.find(t)
        if (alarm != null) {
            val rawSpec = alarm.groupValues[1]
            val weekRepeat = TIME_RE_7.find(rawSpec)
            val spec = if (weekRepeat != null) rawSpec.replaceRange(weekRepeat.range, " ").trim() else rawSpec
            val label = TIME_RE_8.find(spec)?.groupValues?.get(1)
                ?.takeUnless { it.matches(TIME_RE_9) }
            val specNoLabel = if (label != null) spec.substringBeforeLast(label).removeSuffix(" ").removeSuffix("called").removeSuffix("named").removeSuffix("for").trim() else spec
            var m = TimeParser.parse("at $specNoLabel".replace("at at ", "at "), now) ?: return call("set_alarm", "time" to null, "label" to label)
            if (!m.hasTime) return call("set_alarm", "label" to label)
            val bareClock = TIME_RE_10.matchEntire(specNoLabel)
            if (bareClock != null) {
                val hour = bareClock.groupValues[1].toInt() % 12
                val minute = bareClock.groupValues[2].ifEmpty { "0" }.toInt()
                var next = now.toLocalDate().atTime(hour, minute)
                while (!next.isAfter(now)) next = next.plusHours(12)
                m = m.copy(at = next)
            }
            if (specNoLabel.startsWith("in ") || specNoLabel.startsWith("after ")) {
                if (!m.at.isAfter(now)) return call("set_alarm", "label" to label)
                // Clock intents only accept whole minutes; never ring before the requested delay.
                if (m.at.second != 0 || now.nano != 0) m = m.copy(at = m.at.withSecond(0).plusMinutes(1))
            }
            val days = if (weekRepeat != null) weekRepeat.groupValues[1] + "s" else if (m.repeat == "daily") "daily" else if (m.repeat == "weekly") m.at.dayOfWeek.name.lowercase() else null
            return call("set_alarm", "time" to m.at.format(HM), "label" to label, "repeat" to days,
                "date" to if (m.at.toLocalDate() != now.toLocalDate() && days == null) m.at.toLocalDate().toString() else null)
        }
        return null
    }

    fun notes(t: String, now: LocalDateTime): ToolCall? {
        if (t.matches(NOTES_WHOLE_RE)) return call("read_notes")
        NOTES_RE.find(t)
            ?.let { return call("create_note", "text" to it.groupValues[1]) }
        NOTES_RE_2.find(t)?.let { return call("create_note", "text" to it.groupValues[1]) }
        if (t.matches(NOTES_WHOLE_RE_2)) return call("list_reminders")
        NOTES_RE_3.find(t)?.let {
            return call("delete_reminder", "text" to (it.groupValues[2].ifEmpty { if (it.groupValues[1].isNotEmpty()) "all" else "" }))
        }
        val rem = NOTES_RE_4.find(t)
        if (rem != null) {
            val spec = rem.groupValues[1].replace(NOTES_RE_5, "in $1")
            val m = TimeParser.parse(spec, now)?.takeIf { it.hasTime }
            val text = (m?.rest ?: spec).trim()
                .removePrefix("to ").removePrefix("that ").removePrefix("about ").removeSuffix(" at").trim()
            return call("create_reminder", "text" to text.ifEmpty { null }, "time" to m?.at?.format(ISO), "repeat" to m?.repeat?.takeIf { it != "none" })
        }
        return null
    }

    private const val EV = "events?|meetings?|appointments?"
    private const val WEEKDAY = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
    private const val READ_DAY = "today|tonight|tomorrow|this week|next week|(?:on |this |next )?(?:$WEEKDAY)"

    /** Phone calendar (ROADMAP §I.2): "what's tomorrow", "add a meeting with Sam on Friday at 3", "move my dentist to 5 pm". */
    fun calendar(t: String, now: LocalDateTime): ToolCall? {
        val read = CALENDAR_RE.find(t)
            ?: Regex("^(?:whats|what is) on ($READ_DAY)$").find(t)
            ?: Regex("^(?:what do i have|what have i got|do i have anything|whats happening|what is happening)(?: on| for)? ($READ_DAY)$").find(t)
            ?: Regex("^(?:(?:whats|what is) (?:on |in )?|show (?:me )?|read (?:me )?|check |tell me )?(?:my |the )?(?:calendar|schedule|agenda)(?: for| on)?(?: ($READ_DAY))?$").find(t)
            ?: Regex("^(?:do i have |are there |any |what )(?:any )?(?:$EV)(?: do i have)?(?: (?:on |for )?($READ_DAY))?$").find(t)
        if (read != null) return readDay(read.groupValues[1], now.toLocalDate())
        calendarAdd(t, now)?.let { return it }
        CALENDAR_RE_2.find(t)?.let { m ->
            val subject = m.groupValues[1]
            if (!t.startsWith("reschedule") && !t.startsWith("postpone") && !Regex("\\b(?:$EV|calendar)\\b").containsMatchIn(subject)) return@let
            if (CALENDAR_RE_3.containsMatchIn(subject)) return@let
            val spec = m.groupValues[2]
            val at = TimeParser.parse(spec, now) ?: return@let
            val hasDay = Regex("\\b(?:today|tonight|tomorrow|$WEEKDAY|on (?:the )?\\d)").containsMatchIn(spec)
            if (!hasDay && !at.hasTime) return@let
            return call("calendar_move", "query" to eventQuery(subject),
                "date" to if (hasDay) dayOf(spec, at.at, now).toString() else null, "time" to if (at.hasTime) at.at.format(HM) else null)
        }
        CALENDAR_RE_4.find(t)?.let { m ->
            val subject = m.groupValues[1]
            if (!Regex("\\b(?:$EV)\\b|(?:from|on|in) my calendar$").containsMatchIn(subject)) return@let
            if (CALENDAR_RE_3.containsMatchIn(subject)) return@let
            val day = Regex("\\b(today|tonight|tomorrow|(?:on )?(?:$WEEKDAY))\\b").find(subject)?.groupValues?.get(1)
            return call("calendar_delete", "query" to eventQuery(subject), "date" to day?.let { readDayOf(it, now.toLocalDate()).first.toString() })
        }
        return null
    }

    /** "add a meeting with Sam tomorrow at 3 for 30 minutes", "put dentist on my calendar for friday at 10". */
    private fun calendarAdd(t: String, now: LocalDateTime): ToolCall? {
        val typed = Regex("^(?:add|create|schedule|put|book|make|set up|plan)(?: (?:an?|new|my|the))* ($EV)(?: (.*))?$").find(t)
        val named = if (typed == null) CALENDAR_ADD_RE.find(t) else null
        if (typed == null && named == null) return null
        val kind = typed?.groupValues?.get(1)?.removeSuffix("s")
        var spec = (typed?.groupValues?.get(2) ?: listOf(named!!.groupValues[1], named.groupValues[2]).joinToString(" "))
            .replace(CALENDAR_ADD_RE_2, " ").replace(TextNorm.SPACES, " ").trim()
        var minutes: Long? = null
        CALENDAR_ADD_RE_3.find(spec)?.let { d ->
            minutes = TimeParser.duration(d.groupValues[1])?.div(60)
            spec = spec.replaceRange(d.range, " ").replace(TextNorm.SPACES, " ").trim()
        }
        val at = TimeParser.parse(spec, now)
        var title = (at?.rest ?: spec).trim().replace(CALENDAR_ADD_RE_4, "").removeSuffix(" at").removeSuffix(" on").trim()
        if (kind != null && title.startsWith("with ")) title = "$kind $title"
        if (title.isEmpty() && kind != null) title = kind
        val hasDay = Regex("\\b(?:today|tonight|tomorrow|$WEEKDAY|on (?:the )?\\d)").containsMatchIn(spec)
        val time = when {
            at == null -> null
            at.hasTime -> at.at.format(ISO)
            hasDay -> dayOf(spec, at.at, now).toString()
            else -> null
        }
        return call("calendar_add", "title" to title.replaceFirstChar { it.uppercase() }, "time" to time, "minutes" to minutes?.toString())
    }

    /** TimeParser moves "today" with no time to tomorrow once 9 AM passed; a calendar day is just the day said. */
    private fun dayOf(spec: String, at: LocalDateTime, now: LocalDateTime) =
        if (DAY_OF_RE.containsMatchIn(spec)) now.toLocalDate() else at.toLocalDate()

    private fun readDay(word: String, today: LocalDate): ToolCall {
        val (day, days) = readDayOf(word, today)
        return call("calendar_read", "day" to day.takeIf { it != today }?.toString(), "days" to days.takeIf { it > 1 }?.toString())
    }

    /** Day word → first day + how many days. */
    private fun readDayOf(word: String, today: LocalDate): Pair<LocalDate, Int> {
        val w = word.trim().removePrefix("on ")
        return when {
            w.isEmpty() || w == "today" || w == "tonight" -> today to 1
            w == "tomorrow" -> today.plusDays(1) to 1
            w == "this week" -> today to 7
            w == "next week" -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) to 7
            else -> {
                val name = w.substringAfterLast(' ')
                val dow = DayOfWeek.valueOf(name.uppercase())
                var d = today.plusDays(((dow.value - today.dayOfWeek.value + 7) % 7).toLong())
                if (w.startsWith("next ") && d == today) d = d.plusDays(7)
                d to 1
            }
        }
    }

    /** "my dentist appointment tomorrow" → "dentist appointment"; empty = the next event. */
    private fun eventQuery(subject: String): String? = subject
        .replace(EVENT_QUERY_RE, " ")
        .replace(Regex("\\b(?:today|tonight|tomorrow|(?:on |this |next )?(?:$WEEKDAY))\\b"), " ")
        .replace(EVENT_QUERY_RE_2, "")
        .replace(TextNorm.SPACES, " ").trim()
        .takeUnless { it.isEmpty() || it.matches(Regex("(?:my |the |next )?(?:$EV|calendar)")) }

    /** "stop the 11 am alarm", "cancel alarm at 7" → cancel only that alarm (24h time). */
    private fun oneAlarm(t: String, now: LocalDateTime): ToolCall? {
        val m = ONE_ALARM_RE.find(t) ?: return null
        val spec = m.groupValues[1].ifEmpty { m.groupValues[2] }
        if (!spec.any(Char::isDigit)) return call("cancel_alarm")
        val at = TimeParser.parse("at $spec", now)?.takeIf { it.hasTime }?.at ?: return call("cancel_alarm")
        return call("cancel_alarm", "time" to "%02d:%02d".format(at.hour, at.minute))
    }
}

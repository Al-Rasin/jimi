package com.alrasin.jarvis.actions

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

// Regexes built once, not on every call (ROADMAP §L.4).
private val PICK_RE = Regex("\\s+")

/**
 * Phone calendar (ROADMAP §I.2): add / read / move / delete events through CalendarContract.
 * Works offline (the calendar app syncs later). Move and delete confirm first; repeating events are left to the
 * calendar app so one voice command never changes a whole series.
 */
fun calendarTools(context: Context): List<Tool> {
    val cal = PhoneCalendar(context)
    fun noAccess() = ToolResult.fail("I can't use your calendar. Run root setup or allow the calendar permission.")
    return listOf(
        SimpleTool(
            "calendar_read",
            "Say the calendar events for a day or a few days",
            listOf(
                arg("day", "first day, yyyy-MM-dd (default today)", required = false),
                arg("days", "how many days, 1-14 (default 1)", required = false),
            ),
        ) { c, _ ->
            if (!cal.allowed()) return@SimpleTool noAccess()
            val today = LocalDate.now()
            val from = c.arg("day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
            val days = (c.arg("days")?.toIntOrNull() ?: 1).coerceIn(1, 14)
            val events = cal.on(from, days)
            ToolResult.ok(CalendarSpeech.agenda(events, from, days, today), data = events.joinToString("\n") { "${it.start} ${it.title}" })
        },
        SimpleTool(
            "calendar_add",
            "Add an event to the calendar",
            listOf(
                arg("title", "what the event is"),
                arg("time", "start, yyyy-MM-ddTHH:mm, or yyyy-MM-dd for an all-day event"),
                arg("minutes", "how long in minutes (default 60)", required = false),
            ),
        ) { c, _ ->
            val title = c.require("title", "What should I call the event?")
            val time = c.require("time", "When is \"$title\"?")
            if (!cal.allowed()) return@SimpleTool noAccess()
            val whenAt = CalendarSpeech.parseStart(time) ?: return@SimpleTool ToolResult.fail("I didn't get when \"$title\" is.")
            val minutes = (c.arg("minutes")?.toLongOrNull() ?: 60).coerceIn(5, 24 * 60)
            cal.add(title, whenAt, minutes, allDay = time.length <= 10) ?: return@SimpleTool ToolResult.fail("I couldn't add it to your calendar.")
            ToolResult.ok("Added \"$title\" to your calendar ${CalendarSpeech.whenSay(whenAt, LocalDate.now())}.")
        },
        SimpleTool(
            "calendar_move",
            "Move a calendar event to a new day or time",
            listOf(
                arg("query", "which event (words from its title); empty = the next event", required = false),
                arg("date", "new day, yyyy-MM-dd (default same day)", required = false),
                arg("time", "new start time, HH:mm (default same time)", required = false),
            ),
            risk = Risk.CONFIRM,
        ) { c, _ ->
            if (!cal.allowed()) return@SimpleTool noAccess()
            val date = c.arg("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val time = c.arg("time")?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
            if (date == null && time == null) return@SimpleTool ToolResult.fail("Move it to when?")
            val e = cal.find(c.arg("query")) ?: return@SimpleTool ToolResult.fail(CalendarSpeech.notFound(c.arg("query")))
            if (e.recurring) return@SimpleTool ToolResult.fail("\"${e.title}\" repeats. Change repeating events in the calendar app.")
            val old = e.startLocal()
            val start = LocalDateTime.of(date ?: old.toLocalDate(), time ?: old.toLocalTime())
            if (!cal.move(e, start)) return@SimpleTool ToolResult.fail("I couldn't move \"${e.title}\".")
            ToolResult.ok("Moved \"${e.title}\" to ${CalendarSpeech.whenSay(start, LocalDate.now())}.")
        },
        SimpleTool(
            "calendar_delete",
            "Delete a calendar event",
            listOf(
                arg("query", "which event (words from its title); empty = the next event", required = false),
                arg("date", "the day it is on, yyyy-MM-dd", required = false),
            ),
            risk = Risk.CONFIRM,
        ) { c, _ ->
            if (!cal.allowed()) return@SimpleTool noAccess()
            val day = c.arg("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val e = cal.find(c.arg("query"), day) ?: return@SimpleTool ToolResult.fail(CalendarSpeech.notFound(c.arg("query")))
            if (e.recurring) return@SimpleTool ToolResult.fail("\"${e.title}\" repeats. Delete repeating events in the calendar app.")
            if (!cal.delete(e)) return@SimpleTool ToolResult.fail("I couldn't delete \"${e.title}\".")
            ToolResult.ok("Deleted \"${e.title}\" ${CalendarSpeech.whenSay(e.startLocal(), LocalDate.now())}.")
        },
    )
}

/** One event occurrence. [start]/[end] are epoch ms; all-day events are UTC midnights (CalendarContract rule). */
data class CalEvent(
    val id: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val allDay: Boolean,
    val recurring: Boolean,
) {
    fun startLocal(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
        Instant.ofEpochMilli(start).atZone(if (allDay) ZoneOffset.UTC else zone).toLocalDateTime()
}

class PhoneCalendar(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun allowed(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Events on [days] days from [from] (local), repeats expanded, in start order. */
    suspend fun on(from: LocalDate, days: Int, zone: ZoneId = ZoneId.systemDefault()): List<CalEvent> {
        val until = from.plusDays(days.toLong())
        // A day wider on each side so all-day events (UTC) are not lost, then trimmed by local date.
        val a = from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val b = until.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return between(a, b).filter { CalendarSpeech.isOn(it, from, until, zone) }
    }

    /** Best title match among events from now (or on [day]) up to 60 days ahead; blank query = the next one. */
    suspend fun find(query: String?, day: LocalDate? = null): CalEvent? {
        val now = System.currentTimeMillis()
        val list = if (day != null) on(day, 1) else between(now - 3_600_000, now + 60L * 86_400_000).filter { it.end > now }
        return CalendarSpeech.pick(list, query)
    }

    suspend fun add(title: String, start: LocalDateTime, minutes: Long, allDay: Boolean = false): Long? = withContext(Dispatchers.IO) {
        val calId = writableCalendar() ?: return@withContext null
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, calId)
            put(Events.TITLE, title)
            if (allDay) {
                val day = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                put(Events.DTSTART, day)
                put(Events.DTEND, day + 86_400_000)
                put(Events.ALL_DAY, 1)
                put(Events.EVENT_TIMEZONE, "UTC")
            } else {
                val ms = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                put(Events.DTSTART, ms)
                put(Events.DTEND, ms + minutes * 60_000)
                put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        runCatching { resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId) }
            .onFailure { JarvisLog.e("calendar add failed", it) }.getOrNull()
            ?.also { JarvisLog.i("calendar add $it \"$title\" $start") }
    }

    suspend fun move(e: CalEvent, start: LocalDateTime): Boolean = withContext(Dispatchers.IO) {
        val ms = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val values = ContentValues().apply {
            put(Events.DTSTART, ms)
            put(Events.DTEND, ms + (e.end - e.start).coerceAtLeast(15 * 60_000L))
            if (e.allDay) {
                put(Events.ALL_DAY, 0)
                put(Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
        }
        val n = runCatching { resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, e.id), values, null, null) }
            .onFailure { JarvisLog.e("calendar move failed", it) }.getOrDefault(0)
        n > 0
    }

    suspend fun delete(e: CalEvent): Boolean = withContext(Dispatchers.IO) {
        runCatching { resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, e.id), null, null) }
            .onFailure { JarvisLog.e("calendar delete failed", it) }.getOrDefault(0) > 0
    }

    private suspend fun between(fromMs: Long, toMs: Long): List<CalEvent> = withContext(Dispatchers.IO) {
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, fromMs)
            ContentUris.appendId(it, toMs)
        }.build()
        val cols = arrayOf(Instances.EVENT_ID, Instances.TITLE, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.RRULE, Instances.RDATE)
        val out = ArrayList<CalEvent>()
        runCatching {
            resolver.query(uri, cols, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) out += CalEvent(
                    id = c.getLong(0),
                    title = c.getString(1)?.trim().orEmpty().ifEmpty { "Untitled event" },
                    start = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    recurring = !c.getString(5).isNullOrBlank() || !c.getString(6).isNullOrBlank(),
                )
            }
        }.onFailure { JarvisLog.e("calendar read failed", it) }
        out
    }

    /** Primary writable calendar; when the phone has none, a local "Jarvis" calendar (no account, no sync). */
    private fun writableCalendar(): Long? {
        val cols = arrayOf(Calendars._ID, Calendars.IS_PRIMARY, Calendars.CALENDAR_ACCESS_LEVEL)
        val where = "${Calendars.VISIBLE} = 1 AND ${Calendars.CALENDAR_ACCESS_LEVEL} >= ${Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val found = runCatching {
            resolver.query(Calendars.CONTENT_URI, cols, where, null, null)?.use { c ->
                var best: Long? = null
                while (c.moveToNext()) {
                    if (c.getInt(1) == 1) return@use c.getLong(0)
                    if (best == null) best = c.getLong(0)
                }
                best
            }
        }.getOrNull()
        return found ?: createLocal()
    }

    private fun createLocal(): Long? {
        val uri: Uri = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            .build()
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, LOCAL_ACCOUNT)
            put(Calendars.CALENDAR_COLOR, 0xFFFFB300.toInt())
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        return runCatching { resolver.insert(uri, values)?.let(ContentUris::parseId) }
            .onFailure { JarvisLog.e("calendar create failed", it) }.getOrNull()
            ?.also { JarvisLog.i("calendar: made local calendar $it") }
    }

    private companion object {
        const val LOCAL_ACCOUNT = "Jarvis"
    }
}

/** Pure calendar words and picking (unit tested). */
object CalendarSpeech {
    private val DAY_NAME = DateTimeFormatter.ofPattern("EEEE", Locale.US)
    private val DATE = DateTimeFormatter.ofPattern("d MMMM", Locale.US)
    private val FILLER = setOf("my", "the", "a", "an", "event", "meeting", "appointment", "calendar", "on", "in", "from")

    fun parseStart(text: String): LocalDateTime? =
        runCatching { LocalDateTime.parse(text) }.getOrNull()
            ?: runCatching { LocalDate.parse(text).atStartOfDay() }.getOrNull()

    /** True when [e] falls on a local day in [from, until). */
    fun isOn(e: CalEvent, from: LocalDate, until: LocalDate, zone: ZoneId): Boolean {
        if (e.allDay) {
            val first = Instant.ofEpochMilli(e.start).atZone(ZoneOffset.UTC).toLocalDate()
            val last = Instant.ofEpochMilli(e.end - 1).atZone(ZoneOffset.UTC).toLocalDate()
            return first < until && last >= from
        }
        val a = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val b = until.atStartOfDay(zone).toInstant().toEpochMilli()
        return e.start < b && maxOf(e.end, e.start + 1) > a
    }

    /** "10 AM", "3:30 PM". */
    fun clock(t: LocalTime): String =
        t.format(DateTimeFormatter.ofPattern(if (t.minute == 0) "h a" else "h:mm a", Locale.US))

    /** "today", "tomorrow", "on Friday", "on 12 November". */
    fun daySay(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        today.minusDays(1) -> "yesterday"
        else -> if (d > today && d < today.plusDays(7)) "on ${d.format(DAY_NAME)}" else "on ${d.format(DATE)}"
    }

    /** "tomorrow at 10 AM"; midnight = the day only (all-day). */
    fun whenSay(at: LocalDateTime, today: LocalDate): String =
        if (at.toLocalTime() == LocalTime.MIDNIGHT) daySay(at.toLocalDate(), today)
        else "${daySay(at.toLocalDate(), today)} at ${clock(at.toLocalTime())}"

    /** "tomorrow at 5 PM", "Friday", "5 PM" for a move confirm question. */
    fun moveSay(date: String?, time: String?, today: LocalDate): String {
        val d = date?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.let { daySay(it, today).removePrefix("on ") }
        val t = time?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }?.let { "at ${clock(it)}" }
        return listOfNotNull(d, t).joinToString(" ").ifEmpty { "a new time" }
    }

    fun agenda(events: List<CalEvent>, from: LocalDate, days: Int, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): String {
        val span = if (days == 1) daySay(from, today) else if (from == today && days == 7) "this week" else "in the next $days days"
        if (events.isEmpty()) return "Nothing on your calendar $span."
        val items = events.take(8).map { e ->
            val at = e.startLocal(zone)
            val day = if (days > 1) at.format(DAY_NAME) + " " else ""
            if (e.allDay) "${e.title}, ${day.ifEmpty { "all day" }.trim()}" else "${e.title} ${day}at ${clock(at.toLocalTime())}"
        }
        val count = if (events.size == 1) "one event" else "${events.size} events"
        val more = if (events.size > items.size) ", and more" else ""
        return "${span.replaceFirstChar { it.uppercase() }} you have $count: ${list(items)}$more."
    }

    /** Event whose title best fits [query]; blank/filler-only query = the first one. */
    fun pick(events: List<CalEvent>, query: String?): CalEvent? {
        val q = query?.lowercase()?.trim().orEmpty()
        if (q.isEmpty()) return events.firstOrNull()
        events.firstOrNull { it.title.lowercase().contains(q) }?.let { return it }
        val words = q.split(PICK_RE).filter { it !in FILLER }
        if (words.isEmpty()) return events.firstOrNull()
        return events.map { e -> e to words.count { e.title.lowercase().contains(it) } }
            .filter { it.second > 0 }.maxByOrNull { it.second }?.first
    }

    fun notFound(query: String?): String =
        if (query.isNullOrBlank()) "You have no upcoming events." else "I couldn't find \"$query\" on your calendar."

    private fun list(items: List<String>): String =
        if (items.size <= 1) items.joinToString() else items.dropLast(1).joinToString(", ") + ", and " + items.last()
}

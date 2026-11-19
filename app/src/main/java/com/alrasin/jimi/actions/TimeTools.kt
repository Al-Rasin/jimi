package com.alrasin.jimi.actions

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolResult
import com.alrasin.jimi.core.launch
import com.alrasin.jimi.core.pollUntil
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay

// Regexes built once, not on every call (ROADMAP §L.4).
private val CLOCK_HAS_ALARM_RE = Regex("enabled=1, alarmtime=(\\d+)")
private val STOP_TIMER_IN_CLOCK_RE = Regex("(\\d+)x(\\d+)")
private val READ_ALARM_TIME_RE = Regex("^(\\d{1,2})(?::(\\d{2}))?(?::00)?\\s*(am|pm|a m|p m)?$")
private val ISO_DATE_TIME_RE = Regex("^\\d{4}-\\d{2}-\\d{2}[T ](\\d{1,2}:\\d{2})(?::\\d{2})?$")
private val CANCEL_ONE_ALARM_RE = Regex("^(\\d{1,2})(?:[:.](\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?$", RegexOption.IGNORE_CASE)

/** "07:30", "7:30 AM", "7 pm", "19.30" → that clock time; null when it is not a valid time. */
internal fun alarmTime(text: String): LocalTime? {
    // The model sometimes sends a full date-time ("2026-11-20T17:30"); only the clock part matters.
    val clock = ISO_DATE_TIME_RE.matchEntire(text.trim())?.groupValues?.get(1) ?: text.trim()
    val (hs, ms, ap) = CANCEL_ONE_ALARM_RE.find(clock)?.destructured ?: return null
    var h = hs.toInt()
    val m = ms.ifEmpty { "0" }.toInt()
    if (ap.isNotEmpty()) {
        if (h !in 1..12) return null
        val pm = ap.first().lowercaseChar() == 'p'
        h = h % 12 + if (pm) 12 else 0
    } else if (ms.isEmpty()) return null
    return runCatching { LocalTime.of(h, m) }.getOrNull()
}

/** "You have 3 alarms on: 5:10 AM, 5:15 AM and 8:30 PM. Next is 5:10 AM." — sorted from the next one after [now]. */
internal fun alarmSummary(rows: List<ClockDb.Row>, now: LocalTime): String {
    if (rows.isEmpty()) return "You have no alarms on."
    val fmt = java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    val times = rows.map { LocalTime.of(it.hour, it.minute) }.distinct().sorted()
    val ordered = times.filter { it.isAfter(now) } + times.filterNot { it.isAfter(now) }
    val names = ordered.map { it.format(fmt) }
    val list = if (names.size == 1) names[0] else names.dropLast(1).joinToString(", ") + " and " + names.last()
    val count = if (names.size == 1) "1 alarm" else "${names.size} alarms"
    return if (names.size == 1) "You have $count on, at $list." else "You have $count on: $list. Next is ${names[0]}."
}

/** Alarms and timers through the phone's Clock app. */
fun timeTools(context: Context): List<Tool> = listOf(
    SimpleTool("list_alarms", "Say which alarms are on and the next one; read only") { _, _ ->
        val rows = ClockDb.enabledRowsOrNull() ?: return@SimpleTool ToolResult.fail("I can't read the Clock alarms right now.")
        ToolResult.ok(alarmSummary(rows, LocalTime.now()))
    },
    clockTool(
        "set_alarm", "Set an alarm",
        listOf(
            arg("time", "24h time HH:mm, e.g. 07:30"),
            arg("label", "alarm name", required = false),
            arg("repeat", "daily, weekdays, weekends or a weekday name", required = false),
            arg("date", "yyyy-MM-dd if not the next time", required = false),
        ),
    ) { c, _ ->
        val time = c.require("time", "For what time?")
        val plan = try {
            resolveAlarm(time, c.arg("date"), c.arg("repeat"), LocalDateTime.now())
        } catch (e: NumberFormatException) {
            return@clockTool ToolResult.fail("I couldn't read that alarm time. Say it like 7:30 AM.")
        } catch (e: IllegalArgumentException) {
            return@clockTool ToolResult.fail(e.message ?: "I couldn't resolve that alarm time.")
        }
        val h = plan.at.hour
        val m = plan.at.minute
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.arg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = plan.days
        if (days != null) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (!context.launchClock(i)) return@clockTool ToolResult.fail("The clock app didn't take the alarm.")
        val expected = plan.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        var seen: Long? = null
        val check: (Long?) -> Boolean = { next -> seen = next; matchesAlarmMinute(next, expected) }
        var verified = awaitAlarmState(context, matches = check)
        // An earlier alarm hides the new one; sending again would only make a copy.
        if (!verified && seen.let { it == null || it > expected }) {
            JimiLog.i("set_alarm: not seen (next=$seen), sending once more")
            if (context.launchClock(Intent(i))) verified = awaitAlarmState(context, matches = check)
        }
        if (!verified) verified = clockHasAlarm(expected)
        if (!verified) {
            JimiLog.e("set_alarm: Clock request sent, but the requested alarm could not be confirmed (next=$seen)")
            return@clockTool ToolResult.ok("I asked Clock to set ${plan.at.format(ALARM_DATE_FORMAT)}, but could not verify it. Please check Clock.")
        }
        val repeatReply = if (plan.days != null) " I requested${plan.repeatText.removePrefix(",")}." else ""
        ToolResult.ok("Alarm set for ${plan.at.format(ALARM_DATE_FORMAT)}.$repeatReply")
    },
    clockTool("set_timer", "Start a countdown timer", listOf(arg("seconds", "length in seconds"), arg("label", "name", required = false))) { c, _ ->
        val secs = parseTimerSeconds(c.arg("seconds"))
            ?: return@clockTool ToolResult.fail("For how long? Use a positive whole number of seconds.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.arg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val end = System.currentTimeMillis() + secs * 1000L
        if (!context.launchClock(i)) return@clockTool ToolResult.fail("The clock app didn't take the timer.")
        // Clock schedules a running timer as an alarm clock, so the next alarm shows it unless an alarm comes first.
        var seen: Long? = null
        val verified = awaitAlarmState(context) { next -> seen = next; next != null && abs(next - end) <= TIMER_SLACK_MS }
        JimiLog.i("set_timer: verified=$verified next=$seen end=$end")
        if (!verified && seen.let { it == null || it > end + TIMER_SLACK_MS }) {
            return@clockTool ToolResult.ok("I asked Clock to start a ${speakDuration(secs)} timer, but could not verify it. Please check Clock.")
        }
        timerSetResult(secs)
    },
    clockTool(
        "cancel_alarm", "Turn off alarms and timers; time=HH:mm (24h) turns off only that alarm",
        listOf(
            arg("time", "24h HH:mm of the one alarm to turn off", required = false),
            arg("only", "timer = stop timers only, keep alarms", required = false),
        ),
    ) { c, _ ->
        if (c.arg("only") == "timer") return@clockTool cancelTimers(context)
        c.arg("time")?.takeIf { it.isNotBlank() }?.let { time ->
            // A time was asked for: never fall through to "cancel all" when it can't be read.
            val at = alarmTime(time) ?: return@clockTool ToolResult.fail("Which alarm? Say its time, like 7:30 AM.")
            return@clockTool cancelOneAlarm(context, at)
        }
        val alarmsSent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL))
        // MIUI Clock has no handler for DISMISS_TIMER; a running timer is stopped in its screen below.
        val timersSent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_TIMER))
        JimiLog.i("cancel_alarm: Clock accepted alarm request=$alarmsSent, timer request=$timersSent")
        var noNextAlarm = awaitClockIdle(context)
        if (!noNextAlarm && timerRunning()) {
            stopTimerInClock(context)
            noNextAlarm = awaitClockIdle(context)
        }
        if (!noNextAlarm) {
            // MIUI Clock ignores DISMISS_ALARM for repeating alarms; switch the rows off directly (root).
            val left = ClockDb.disable(ClockDb.enabledRows())
            // Clock reschedules a few seconds after TIME_SET, so wait longer here.
            noNextAlarm = left.isEmpty() && awaitClockIdle(context, tries = 20)
        }
        JimiLog.i("cancel_alarm: no next alarm confirmed=$noNextAlarm")
        if (!alarmsSent && !noNextAlarm) return@clockTool ToolResult.fail("The clock didn't accept the turn-off request. Check the Clock app.")
        cancelAlarmResult(noNextAlarm)
    },
)

internal fun Context.launchClock(intent: Intent): Boolean {
    val manager = packageManager
    val handlers = manager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { result ->
        ClockHandler(
            result.activityInfo.packageName,
            result.activityInfo.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
        )
    }
    val defaultPackage = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val clock = pickClockPackage(handlers, defaultPackage) ?: return false
    intent.setPackage(clock)
    JimiLog.i("Clock intent=${intent.action} package=$clock")
    return launch(intent)
}

private suspend fun awaitAlarmState(context: Context, matches: (Long?) -> Boolean): Boolean {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return false
    var failed = false
    val matched = pollUntil(5, 500) {
        if (failed) return@pollUntil true
        val next = try {
            manager.nextAlarmClock?.triggerTime
        } catch (e: RuntimeException) {
            JimiLog.e("Could not read the next Clock alarm", e)
            failed = true
            return@pollUntil true
        }
        matches(next)
    }
    return matched && !failed
}

/** True when Clock has nothing scheduled. Root counts only Clock's entries; without root, any next alarm clock counts. */
private suspend fun awaitClockIdle(context: Context, tries: Int = 5): Boolean {
    if (ClockDb.scheduledCount() == null) return awaitAlarmState(context) { it == null }
    return pollUntil(tries, 500) { ClockDb.scheduledCount() == 0 }
}

private const val TIMER_SLACK_MS = 5_000L

/** Second check when an earlier alarm hides the new one: an enabled row in the Clock alarm table (root only). */
private suspend fun clockHasAlarm(at: Long): Boolean {
    val out = RootShell.run("content query --uri content://com.android.deskclock/alarm --projection enabled:alarmtime", log = false).out
    return CLOCK_HAS_ALARM_RE.findAll(out).any { abs(it.groupValues[1].toLong() - at) < 60_000 }
}

/** A running Clock timer is a TIMER_ALERT alarm (root only; false without root). */
private suspend fun timerRunning(): Boolean =
    pendingTimerAlert(RootShell.run("dumpsys alarm", log = false).out)

/** Only the pending list counts; the stats part below keeps timers that already rang. */
internal fun pendingTimerAlert(dump: String): Boolean =
    dump.substringBefore("Pending alarms per uid").substringBefore("Top Alarms").contains("TIMER_ALERT")

/** Opens the Clock timer tab and taps its stop button (bottom left on MIUI Clock). Best try. */
private suspend fun stopTimerInClock(context: Context) {
    if (!context.launchClock(Intent(AlarmClock.ACTION_SHOW_TIMERS))) return
    delay(1_500)
    val m = context.resources.displayMetrics
    val size = STOP_TIMER_IN_CLOCK_RE.find(RootShell.run("wm size", log = false).out)?.destructured
    val w = size?.component1()?.toIntOrNull() ?: m.widthPixels
    val h = size?.component2()?.toIntOrNull() ?: m.heightPixels
    JimiLog.i("cancel_alarm: tapping timer stop in Clock")
    RootInput.tap((w * 0.387).toInt(), (h * 0.935).toInt())
    delay(500)
}

internal fun hasScheduledAlarm(next: Long?): Boolean = next != null

internal fun timerSetResult(seconds: Int): ToolResult =
    ToolResult.ok("Timer set for ${speakDuration(seconds)}.")

/** Stops running timers only; alarms are left alone. */
private suspend fun cancelTimers(context: Context): ToolResult {
    val sent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_TIMER))
    if (timerRunning()) stopTimerInClock(context)
    // Clock drops the timer alarm a moment after the tap.
    val stillRunning = !pollUntil(tries = 6, pauseMs = 300) { !timerRunning() }
    JimiLog.i("cancel_alarm: timers only, request=$sent still running=$stillRunning")
    return if (stillRunning) ToolResult.fail("I couldn't stop the timer. Check the Clock app.")
    else ToolResult.ok("Okay, the timer is off.")
}

internal fun cancelAlarmResult(noNextAlarm: Boolean): ToolResult =
    if (noNextAlarm) ToolResult.ok("Okay, alarms and timers are off.")
    else ToolResult.fail("I couldn't turn off every alarm. Some are still on in Clock.")

internal fun matchesAlarmMinute(actual: Long?, expected: Long): Boolean =
    actual != null && Math.floorDiv(actual, 60_000L) == Math.floorDiv(expected, 60_000L)

internal fun parseTimerSeconds(value: String?): Int? {
    val seconds = value?.toDoubleOrNull() ?: return null
    return seconds.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE && it % 1 == 0.0 }?.toInt()
}

private val ALARM_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.ENGLISH)

internal data class AlarmPlan(val at: LocalDateTime, val days: List<Int>?, val repeatText: String)

internal fun resolveAlarm(time: String, date: String?, repeat: String?, now: LocalDateTime): AlarmPlan {
    val (timeDate, clock) = readAlarmTime(time) ?: throw IllegalArgumentException("Use an alarm time like 07:00.")
    val requestedDate = date?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
            ?: throw IllegalArgumentException("Use an alarm date like 2026-10-23.")
    } ?: timeDate?.takeIf { it != now.toLocalDate() || !now.toLocalDate().atTime(clock).isAfter(now) }
    val repetition = repeat?.trim()?.lowercase(Locale.ROOT)?.removePrefix("every ")?.takeUnless { it == "none" || it.isEmpty() }
    val repeatDays: List<DayOfWeek>? = when (repetition) {
        null -> null
        "daily", "day", "everyday" -> DayOfWeek.entries
        "weekdays", "weekday" -> DayOfWeek.entries.take(5)
        "weekends", "weekend" -> listOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        else -> listOf(
            runCatching { DayOfWeek.valueOf(repetition.removeSuffix("s").uppercase(Locale.ROOT)) }.getOrNull()
                ?: throw IllegalArgumentException("Alarm repeat must be daily, weekdays, weekends or a weekday name."),
        )
    }
    require(requestedDate == null || repeatDays == null) { "I can't combine a start date with a repeating Clock alarm. No alarm was set." }
    var at = now.toLocalDate().atTime(clock)
    if (!at.isAfter(now)) at = at.plusDays(1)
    if (repeatDays != null) {
        while (at.dayOfWeek !in repeatDays) at = at.plusDays(1)
    }
    if (requestedDate != null && requestedDate != at.toLocalDate()) {
        val requested = requestedDate.atTime(clock).format(ALARM_DATE_FORMAT)
        throw IllegalArgumentException("I can't set a one-time Clock alarm for $requested. No alarm was set. Ask for a reminder on that date instead.")
    }
    val days = when (repeatDays?.size) {
        null -> null
        7 -> (1..7).toList()
        else -> repeatDays.map(::calendarDay)
    }
    val suffix = when (repeatDays?.size) {
        null -> ""
        7 -> ", repeating daily"
        1 -> ", repeating every ${repeatDays.single().name.lowercase(Locale.ROOT)}"
        5 -> ", repeating on weekdays"
        else -> ", repeating on weekends"
    }
    return AlarmPlan(at, days, suffix)
}

/** "07:30", "7:30", "7 am", "7:30 PM", "2026-10-23T07:00", "2026-10-23 07:00" → optional date + time. */
internal fun readAlarmTime(text: String): Pair<LocalDate?, LocalTime>? {
    val t = text.trim().lowercase(Locale.ROOT).replace(".", "")
    runCatching { LocalDateTime.parse(t.replace(' ', 'T')) }.getOrNull()
        ?.takeIf { it.second == 0 }?.let { return it.toLocalDate() to it.toLocalTime() }
    val m = READ_ALARM_TIME_RE.find(t) ?: return null
    var h = m.groupValues[1].toInt()
    val min = m.groupValues[2].ifEmpty { "0" }.toInt()
    val half = m.groupValues[3].replace(" ", "")
    if (half.isEmpty() && m.groupValues[2].isEmpty()) return null
    if (min > 59 || (half.isEmpty() && h > 23) || (half.isNotEmpty() && h !in 1..12)) return null
    if (half == "pm" && h < 12) h += 12
    if (half == "am" && h == 12) h = 0
    return null to LocalTime.of(h, min)
}

private fun calendarDay(d: DayOfWeek) = when (d) {
    DayOfWeek.SUNDAY -> Calendar.SUNDAY
    DayOfWeek.MONDAY -> Calendar.MONDAY
    DayOfWeek.TUESDAY -> Calendar.TUESDAY
    DayOfWeek.WEDNESDAY -> Calendar.WEDNESDAY
    DayOfWeek.THURSDAY -> Calendar.THURSDAY
    DayOfWeek.FRIDAY -> Calendar.FRIDAY
    DayOfWeek.SATURDAY -> Calendar.SATURDAY
}

fun speakTime(h: Int, m: Int): String {
    val h12 = if (h % 12 == 0) 12 else h % 12
    val ampm = if (h < 12) "AM" else "PM"
    return if (m == 0) "$h12 $ampm" else "$h12:${"%02d".format(m)} $ampm"
}

fun speakDuration(secs: Int): String {
    val h = secs / 3600
    val m = secs % 3600 / 60
    val s = secs % 60
    return listOfNotNull(
        h.takeIf { it > 0 }?.let { "$it hour${if (it > 1) "s" else ""}" },
        m.takeIf { it > 0 }?.let { "$it minute${if (it > 1) "s" else ""}" },
        s.takeIf { it > 0 }?.let { "$it second${if (it > 1) "s" else ""}" },
    ).joinToString(" and ").ifEmpty { "0 seconds" }
}

/** Clock's own search mode finds the alarm at that time. MIUI Clock may ignore it, so check. */
private suspend fun cancelOneAlarm(context: Context, target: LocalTime): ToolResult {
    val time = target.toString()
    val spoken = target.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH))
    val wasNext = isAt(context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime, target)
    val sent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_ALARM)
        .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_TIME)
        .putExtra(AlarmClock.EXTRA_HOUR, target.hour)
        .putExtra(AlarmClock.EXTRA_MINUTES, target.minute))
    val off = (sent && wasNext && awaitAlarmState(context) { !isAt(it, target) }) || disableRowsAt(target)
    JimiLog.i("cancel_alarm: one alarm $time sent=$sent off=$off")
    return when {
        off -> ToolResult.ok("Okay, the $spoken alarm is off.")
        rowsAt(ClockDb.enabledRows(), target).isEmpty() && !wasNext -> ToolResult.ok("You have no $spoken alarm on.")
        else -> ToolResult.fail("Clock didn't turn off the $spoken alarm. Please switch it off in Clock.")
    }
}

/** Root fallback: switch off enabled Clock rows at [time]; true only if there were some and all are off now. */
private suspend fun disableRowsAt(time: LocalTime): Boolean {
    val rows = rowsAt(ClockDb.enabledRows(), time)
    return rows.isNotEmpty() && ClockDb.disable(rows).isEmpty()
}

internal fun rowsAt(rows: List<ClockDb.Row>, time: LocalTime): List<ClockDb.Row> =
    rows.filter { it.hour == time.hour && it.minute == time.minute }

private fun isAt(triggerMillis: Long?, time: LocalTime): Boolean = triggerMillis != null &&
    LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerMillis), ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0) == time

/** A Clock tool that brings the user back if MIUI Clock jumps to the front (it ignores EXTRA_SKIP_UI). */
private fun clockTool(
    name: String,
    description: String,
    args: List<com.alrasin.jimi.core.ArgSpec> = emptyList(),
    block: suspend (com.alrasin.jimi.core.ToolCall, com.alrasin.jimi.core.ToolContext) -> ToolResult,
) = SimpleTool(name, description, args) { c, x ->
    // MIUI drops the accessibility service at times (e.g. after an update); it is needed to come back.
    if (JimiAccessibilityService.instance == null) reviveScreen()
    val before = foregroundPackage()
    val result = block(c, x)
    leaveClockIfItJumped(before)
    result
}

/** Top app window's package, Jimi included (agentRoot skips Jimi). */
private fun foregroundPackage(): String? {
    val service = JimiAccessibilityService.instance ?: return null
    val top = service.windows.filter { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }
        .maxByOrNull { it.layer }
    return (top?.root ?: service.rootInActiveWindow)?.packageName?.toString()
}

internal fun clockJumpedToFront(before: String?, now: String?): Boolean =
    now != null && now != before && CLOCK_PACKAGES.any { now == it }

private val CLOCK_PACKAGES = listOf("com.android.deskclock", "com.google.android.deskclock", "com.sec.android.app.clockpackage")

private suspend fun leaveClockIfItJumped(before: String?) {
    val service = JimiAccessibilityService.instance ?: return JimiLog.i("Clock: no screen control, can't come back")
    // Clock opens a little after the intent, so look for a short while.
    val jumped = pollUntil(tries = 6, pauseMs = 250) { clockJumpedToFront(before, foregroundPackage()) }
    if (!jumped) return JimiLog.i("Clock stayed behind (before=$before)")
    service.global(AccessibilityService.GLOBAL_ACTION_BACK)
    JimiLog.i("Clock jumped to front over $before; pressed back")
}

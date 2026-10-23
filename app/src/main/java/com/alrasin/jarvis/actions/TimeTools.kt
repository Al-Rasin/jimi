package com.alrasin.jarvis.actions

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.AlarmClock
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale

/** Alarms and timers through the phone's Clock app. */
fun timeTools(context: Context): List<Tool> = listOf(
    SimpleTool(
        "set_alarm", "Set an alarm",
        listOf(
            arg("time", "24h time HH:mm, e.g. 07:30"),
            arg("label", "alarm name", required = false),
            arg("repeat", "daily or a weekday name", required = false),
            arg("date", "yyyy-MM-dd if not the next time", required = false),
        ),
    ) { c, _ ->
        val time = c.arg("time") ?: return@SimpleTool ToolResult.fail("For what time?")
        val plan = try {
            resolveAlarm(time, c.arg("date"), c.arg("repeat"), LocalDateTime.now())
        } catch (e: IllegalArgumentException) {
            return@SimpleTool ToolResult.fail(e.message ?: "I couldn't resolve that alarm time.")
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
        if (!context.launchClock(i)) return@SimpleTool ToolResult.fail("The clock app didn't take the alarm.")
        val expected = plan.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (!awaitAlarmState(context) { next ->
                if (next != null && !matchesAlarmMinute(next, expected)) {
                    JarvisLog.i(if (next < expected) {
                        "set_alarm: requested alarm cannot be seen past an earlier alarm"
                    } else {
                        "set_alarm: next alarm differs from requested time"
                    })
                }
                matchesAlarmMinute(next, expected)
            }) {
            JarvisLog.e("set_alarm: Clock request sent, but the requested alarm could not be confirmed")
            return@SimpleTool ToolResult.ok("I asked Clock to set ${plan.at.format(ALARM_DATE_FORMAT)}, but could not verify it. Please check Clock.")
        }
        val repeatReply = if (plan.days != null) " I requested${plan.repeatText.removePrefix(",")}." else ""
        ToolResult.ok("Alarm set for ${plan.at.format(ALARM_DATE_FORMAT)}.$repeatReply")
    },
    SimpleTool("set_timer", "Start a countdown timer", listOf(arg("seconds", "length in seconds"), arg("label", "name", required = false))) { c, _ ->
        val secs = parseTimerSeconds(c.arg("seconds"))
            ?: return@SimpleTool ToolResult.fail("For how long? Use a positive whole number of seconds.")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.arg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (!context.launchClock(i)) return@SimpleTool ToolResult.fail("The clock app didn't take the timer.")
        JarvisLog.i("set_timer: Clock request sent; Android does not expose timer confirmation")
        timerSetResult(secs)
    },
    SimpleTool("cancel_alarm", "Turn off alarms and timers") { _, _ ->
        val alarmsSent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_ALARM)
            .putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL))
        val timersSent = context.launchClock(Intent(AlarmClock.ACTION_DISMISS_TIMER))
        if (!alarmsSent || !timersSent) {
            JarvisLog.e("cancel_alarm: Clock accepted alarm request=$alarmsSent, timer request=$timersSent")
            return@SimpleTool ToolResult.fail("The clock didn't accept every turn-off request. Check the Clock app.")
        }
        val noNextAlarm = awaitAlarmState(context) { it == null }
        JarvisLog.i("cancel_alarm: no next alarm confirmed=$noNextAlarm; timer dismissal cannot be verified")
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
    JarvisLog.i("Clock intent=${intent.action} package=$clock")
    return launch(intent)
}

private suspend fun awaitAlarmState(context: Context, matches: (Long?) -> Boolean): Boolean {
    val manager = context.getSystemService(AlarmManager::class.java) ?: return false
    repeat(5) { attempt ->
        if (attempt > 0) delay(500)
        val next = try {
            manager.nextAlarmClock?.triggerTime
        } catch (e: RuntimeException) {
            JarvisLog.e("Could not read the next Clock alarm", e)
            return false
        }
        if (matches(next)) return true
    }
    return false
}

internal fun hasScheduledAlarm(next: Long?): Boolean = next != null

internal fun timerSetResult(seconds: Int): ToolResult =
    ToolResult.ok("Timer set for ${speakDuration(seconds)}.")

internal fun cancelAlarmResult(noNextAlarm: Boolean): ToolResult = ToolResult.ok(
    if (noNextAlarm) "Okay, alarms and timers are off." else "I asked the clock to turn them off."
)

internal fun matchesAlarmMinute(actual: Long?, expected: Long): Boolean =
    actual != null && Math.floorDiv(actual, 60_000L) == Math.floorDiv(expected, 60_000L)

internal fun parseTimerSeconds(value: String?): Int? {
    val seconds = value?.toDoubleOrNull() ?: return null
    return seconds.takeIf { it.isFinite() && it >= 1 && it <= Int.MAX_VALUE && it % 1 == 0.0 }?.toInt()
}

private val ALARM_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.ENGLISH)

internal data class AlarmPlan(val at: LocalDateTime, val days: List<Int>?, val repeatText: String)

internal fun resolveAlarm(time: String, date: String?, repeat: String?, now: LocalDateTime): AlarmPlan {
    val clock = runCatching { LocalTime.parse(time) }.getOrNull()
    require(clock != null && clock.second == 0 && clock.nano == 0) { "Use an alarm time like 07:00." }
    val requestedDate = date?.let {
        runCatching { LocalDate.parse(it) }.getOrNull()
            ?: throw IllegalArgumentException("Use an alarm date like 2026-10-23.")
    }
    val repetition = repeat?.trim()?.lowercase(Locale.ROOT)?.takeUnless { it == "none" || it.isEmpty() }
    val weekday = if (repetition != null && repetition != "daily") {
        runCatching { DayOfWeek.valueOf(repetition.uppercase(Locale.ROOT)) }.getOrNull()
            ?: throw IllegalArgumentException("Alarm repeat must be daily or a weekday name.")
    } else null
    require(requestedDate == null || repetition == null) { "I can't combine a start date with a repeating Clock alarm. No alarm was set." }
    var at = now.toLocalDate().atTime(clock)
    if (!at.isAfter(now)) at = at.plusDays(1)
    if (weekday != null) {
        while (at.dayOfWeek != weekday) at = at.plusDays(1)
    }
    if (requestedDate != null && requestedDate != at.toLocalDate()) {
        val requested = requestedDate.atTime(clock).format(ALARM_DATE_FORMAT)
        throw IllegalArgumentException("I can't set a one-time Clock alarm for $requested. No alarm was set. Ask for a reminder on that date instead.")
    }
    val days = when {
        repetition == "daily" -> (1..7).toList()
        weekday != null -> listOf(calendarDay(weekday))
        else -> null
    }
    val suffix = when {
        repetition == "daily" -> ", repeating daily"
        weekday != null -> ", repeating every ${weekday.name.lowercase(Locale.ROOT)}"
        else -> ""
    }
    return AlarmPlan(at, days, suffix)
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

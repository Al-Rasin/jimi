package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import java.time.DayOfWeek
import java.util.Calendar

/** Alarms and timers through the phone's Clock app (no UI shown). */
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
        val (h, m) = time.split(":").map { it.toInt() }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.arg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        val days = when (val r = c.arg("repeat")) {
            null -> null
            "daily" -> (1..7).toList()
            else -> runCatching { listOf(calendarDay(DayOfWeek.valueOf(r.uppercase()))) }.getOrNull()
        }
        if (days != null) i.putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        if (!context.launch(i)) return@SimpleTool ToolResult.fail("The clock app didn't take the alarm.")
        val spoken = speakTime(h, m)
        ToolResult.ok("Alarm set for $spoken${if (c.arg("repeat") != null) ", ${c.arg("repeat")}" else ""}.")
    },
    SimpleTool("set_timer", "Start a countdown timer", listOf(arg("seconds", "length in seconds"), arg("label", "name", required = false))) { c, _ ->
        val secs = c.arg("seconds")?.toDoubleOrNull()?.toInt() ?: return@SimpleTool ToolResult.fail("For how long?")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, secs)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        c.arg("label")?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        if (context.launch(i)) ToolResult.ok("Timer set for ${speakDuration(secs)}.") else ToolResult.fail("The clock app didn't take the timer.")
    },
    SimpleTool("cancel_alarm", "Turn off alarms and timers") { _, _ ->
        context.launch(Intent(AlarmClock.ACTION_DISMISS_ALARM).putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL))
        context.launch(Intent(AlarmClock.ACTION_DISMISS_TIMER))
        ToolResult.ok("Okay, I asked the clock to turn them off.")
    },
)

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

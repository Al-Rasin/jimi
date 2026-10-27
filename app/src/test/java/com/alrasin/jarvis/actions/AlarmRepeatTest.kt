package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.util.Calendar

class AlarmRepeatTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)   // Thursday

    @Test fun dateAndTimeInOneString() {
        assertEquals(now.plusDays(1).withHour(7), resolveAlarm("2026-10-23T07:00", null, null, now).at)
        assertEquals(now.plusDays(1).withHour(7), resolveAlarm("2026-10-23 07:00", null, null, now).at)
        assertEquals(now.withHour(19).withMinute(30), resolveAlarm("7:30 pm", null, null, now).at)
    }

    @Test fun weekdaysAndWeekends() {
        val week = resolveAlarm("07:00", null, "weekdays", now)
        assertEquals(now.plusDays(1).withHour(7), week.at)
        assertEquals(listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY), week.days)
        val end = resolveAlarm("07:00", null, "weekends", now)
        assertEquals(now.plusDays(2).withHour(7), end.at)
        assertEquals(setOf(Calendar.SATURDAY, Calendar.SUNDAY), end.days!!.toSet())
        assertEquals((1..7).toList(), resolveAlarm("07:00", null, "every day", now).days)
        assertEquals(listOf(Calendar.MONDAY), resolveAlarm("07:00", null, "every mondays", now).days)
    }
}

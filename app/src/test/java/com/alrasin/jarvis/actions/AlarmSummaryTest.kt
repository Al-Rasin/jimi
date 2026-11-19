package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class AlarmSummaryTest {
    private fun row(id: Long, h: Int, m: Int) = ClockDb.Row(id, h, m)

    @Test fun isoDateTimeGivesClock() {
        assertEquals(LocalTime.of(17, 30), alarmTime("2026-11-20T17:30"))
        assertEquals(LocalTime.of(5, 10), alarmTime("2026-11-20 05:10:00"))
    }

    @Test fun summaryCountsAndStartsAtNext() {
        assertEquals("You have no alarms on.", alarmSummary(emptyList(), LocalTime.of(10, 0)))
        assertEquals("You have 1 alarm on, at 5:10 AM.", alarmSummary(listOf(row(1, 5, 10)), LocalTime.of(10, 0)))
        val rows = listOf(row(1, 5, 10), row(2, 5, 15), row(3, 5, 20), row(4, 17, 30))
        assertEquals(
            "You have 4 alarms on: 5:30 PM, 5:10 AM, 5:15 AM and 5:20 AM. Next is 5:30 PM.",
            alarmSummary(rows, LocalTime.of(10, 0)),
        )
    }
}

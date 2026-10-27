package com.alrasin.jarvis.actions

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.util.Calendar

class AlarmDatesTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    @Test fun nextOccurrenceIncludesDate() {
        assertEquals(now.withHour(19), resolveAlarm("19:00", null, null, now).at)
        assertEquals(now.plusDays(1).withHour(7), resolveAlarm("07:00", "2026-10-23", null, now).at)
        assertEquals(now.plusDays(1), resolveAlarm("10:00", null, null, now).at)
    }

    @Test fun unsupportedDatesNeverBecomeAnotherDay() {
        for (date in listOf("2026-10-24", "2026-10-21", "2026-10-22")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                resolveAlarm("07:00", date, null, now)
            }
            assertTrue(error.message!!.contains("No alarm was set"))
        }
        val error = assertThrows(IllegalArgumentException::class.java) {
            resolveAlarm("07:00", "2026-10-30", null, now)
        }
        assertTrue(error.message!!.contains("Friday 30 October, 7:00 AM"))
    }

    @Test fun repeatsHaveCorrectFirstDateAndCalendarDays() {
        val friday = resolveAlarm("07:00", null, "friday", now)
        assertEquals(now.plusDays(1).withHour(7), friday.at)
        assertEquals(listOf(Calendar.FRIDAY), friday.days)
        assertEquals((1..7).toList(), resolveAlarm("07:00", null, "daily", now).days)
        assertEquals(now.plusWeeks(1).withHour(7), resolveAlarm("07:00", null, "thursday", now).at)
        assertNull(resolveAlarm("07:00", null, "none", now).days)
    }

    @Test fun malformedArgumentsFailBeforeLaunching() {
        for (time in listOf("oops", "24:00", "12:60", "12", "07:00:30")) {
            assertThrows(IllegalArgumentException::class.java) { resolveAlarm(time, null, null, now) }
        }
        assertThrows(IllegalArgumentException::class.java) { resolveAlarm("07:00", "2026-02-30", null, now) }
        assertThrows(IllegalArgumentException::class.java) { resolveAlarm("07:00", null, "fortnightly", now) }
        assertThrows(IllegalArgumentException::class.java) { resolveAlarm("07:00", "2026-10-23", "daily", now) }
    }

    @Test fun midnightAndNoonStayDistinct() {
        assertEquals(now.plusDays(1).withHour(0), resolveAlarm("00:00", null, null, now).at)
        assertEquals(now.withHour(12), resolveAlarm("12:00", null, null, now).at)
    }
}

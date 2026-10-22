package com.alrasin.jarvis.actions

import com.alrasin.jarvis.data.Reminder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ReminderDatesTest {
    private val utc = ZoneId.of("UTC")
    private fun ms(at: String, zone: ZoneId = utc) = LocalDateTime.parse(at).atZone(zone).toInstant().toEpochMilli()
    private fun reminder(at: String, repeat: String, zone: ZoneId = utc) =
        Reminder(text = "test", dueMs = ms(at, zone), repeat = repeat)

    @Test fun oneTimeAndUnknownRecurrencesDoNotRepeat() {
        assertNull(ReminderScheduler.next(reminder("2026-10-22T07:00", "none"), ms("2026-10-22T07:00"), utc))
        assertNull(ReminderScheduler.next(reminder("2026-10-22T07:00", "monthly"), ms("2026-10-22T07:00"), utc))
    }

    @Test fun dailyKeepsWallClockTime() {
        assertEquals(ms("2026-10-23T07:00"), ReminderScheduler.next(
            reminder("2026-10-22T07:00", "daily"), ms("2026-10-22T07:00"), utc))
    }

    @Test fun rebootSkipsMissedDailyOccurrences() {
        assertEquals(ms("2026-10-26T07:00"), ReminderScheduler.next(
            reminder("2026-10-22T07:00", "daily"), ms("2026-10-25T09:00"), utc))
    }

    @Test fun overdueDailyCanStillRepeatLaterToday() {
        assertEquals(ms("2026-10-25T07:00"), ReminderScheduler.next(
            reminder("2026-10-22T07:00", "daily"), ms("2026-10-25T06:00"), utc))
    }

    @Test fun weeklyPreservesWeekdayAfterLongDowntime() {
        assertEquals(ms("2026-11-13T07:00"), ReminderScheduler.next(
            reminder("2026-10-23T07:00", "weekly"), ms("2026-11-06T09:00"), utc))
    }

    @Test fun exactOccurrenceAdvancesToStrictlyFutureTime() {
        assertEquals(ms("2026-10-30T07:00"), ReminderScheduler.next(
            reminder("2026-10-16T07:00", "weekly"), ms("2026-10-23T07:00"), utc))
    }

    @Test fun springDstPreservesMorningHour() {
        val zone = ZoneId.of("America/New_York")
        val due = reminder("2026-03-07T07:00", "daily", zone)
        assertEquals(ms("2026-03-08T07:00", zone), ReminderScheduler.next(due, due.dueMs, zone))
        assertEquals(23 * 60 * 60 * 1000L, ReminderScheduler.next(due, due.dueMs, zone)!! - due.dueMs)
    }

    @Test fun autumnDstPreservesMorningHour() {
        val zone = ZoneId.of("America/New_York")
        val due = reminder("2026-10-31T07:00", "daily", zone)
        assertEquals(ms("2026-11-01T07:00", zone), ReminderScheduler.next(due, due.dueMs, zone))
        assertEquals(25 * 60 * 60 * 1000L, ReminderScheduler.next(due, due.dueMs, zone)!! - due.dueMs)
    }

    @Test fun springGapMovesToFirstValidCorrespondingTime() {
        val zone = ZoneId.of("America/New_York")
        val due = reminder("2026-03-07T02:30", "daily", zone)
        assertEquals(ms("2026-03-08T03:30", zone), ReminderScheduler.next(due, due.dueMs, zone))
    }

    @Test fun missedSpringGapDoesNotShiftLaterOccurrences() {
        val zone = ZoneId.of("America/New_York")
        val due = reminder("2026-03-07T02:30", "daily", zone)
        assertEquals(ms("2026-03-10T02:30", zone), ReminderScheduler.next(due, ms("2026-03-09T09:00", zone), zone))
    }

    @Test fun changedTimezoneUsesPersistedInstantInCurrentZone() {
        val due = reminder("2026-10-22T07:00", "daily")
        val dhaka = ZoneId.of("Asia/Dhaka")
        assertEquals(ms("2026-10-23T13:00", dhaka), ReminderScheduler.next(due, due.dueMs, dhaka))
    }

    @Test fun clockMovedBackwardsStillAdvancesBeyondOriginalOccurrence() {
        val due = reminder("2026-10-22T07:00", "daily")
        assertEquals(ms("2026-10-23T07:00"), ReminderScheduler.next(due, ms("2026-10-20T09:00"), utc))
    }
}

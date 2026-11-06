package com.alrasin.jarvis.actions

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class AlarmCheckTest {
    private val expected = LocalDateTime.of(2026, 10, 23, 7, 0)
        .atZone(ZoneId.of("Asia/Dhaka")).toInstant().toEpochMilli()

    @Test fun matchesTheRequestedMinute() {
        assertTrue(matchesAlarmMinute(expected, expected))
        assertTrue(matchesAlarmMinute(expected + 59_999, expected))
    }

    @Test fun missingEarlierLaterAndDifferentDayAlarmsDoNotConfirm() {
        assertFalse(matchesAlarmMinute(null, expected))
        assertFalse(matchesAlarmMinute(expected - 1, expected))
        assertFalse(matchesAlarmMinute(expected + 60_000, expected))
        assertFalse(matchesAlarmMinute(expected + 86_400_000, expected))
        assertFalse(matchesAlarmMinute(expected - 86_400_000, expected))
    }

    @Test fun existingAlarmIsAcceptedEvenWhenItHidesTheRequestedAlarm() {
        assertFalse(hasScheduledAlarm(null))
        for (next in listOf(expected, expected - 60_000, expected + 60_000, expected + 86_400_000)) {
            assertTrue(hasScheduledAlarm(next))
        }
    }

    @Test fun acceptedTimerReturnsSuccess() {
        val result = timerSetResult(1200)
        assertTrue(result.ok)
        assertEquals("Timer set for 20 minutes.", result.say)
    }

    @Test fun cancellationIsHonestAboutAlarmsLeftOn() {
        val cleared = cancelAlarmResult(true)
        assertTrue(cleared.ok)
        assertEquals("Okay, alarms and timers are off.", cleared.say)
        val remaining = cancelAlarmResult(false)
        assertFalse(remaining.ok)
        assertEquals("I couldn't turn off every alarm. Some are still on in Clock.", remaining.say)
    }

    @Test fun timerAcceptsOnlyPositiveWholeSecondsWithinIntentRange() {
        assertEquals(1200, parseTimerSeconds("1200"))
        assertEquals(20, parseTimerSeconds("20.0"))
        assertEquals(Int.MAX_VALUE, parseTimerSeconds(Int.MAX_VALUE.toString()))
        for (value in listOf(null, "", "oops", "0", "-20", "0.5", "20.5", "NaN", "Infinity", "2147483648")) {
            assertNull(value, parseTimerSeconds(value))
        }
    }
}

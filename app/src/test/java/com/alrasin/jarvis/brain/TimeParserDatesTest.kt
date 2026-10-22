package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class TimeParserDatesTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    private fun check(text: String, expected: String, reference: LocalDateTime = now) {
        assertEquals(text, LocalDateTime.parse(expected), TimeParser.parse(text, reference)?.at)
    }

    @Test fun tomorrowMorning() = check("tomorrow morning", "2026-10-23T09:00")
    @Test fun tomorrowMorningClock() = check("tomorrow morning at eight", "2026-10-23T08:00")
    @Test fun tomorrowEvening() = check("tomorrow evening", "2026-10-23T18:00")
    @Test fun nextFriday() = check("next Friday at 7 am", "2026-10-30T07:00")
    @Test fun thisFriday() = check("this Friday at 7 am", "2026-10-23T07:00")
    @Test fun bareFriday() = check("Friday at 7 am", "2026-10-23T07:00")
    @Test fun onNextFriday() = check("on next Friday at seven am", "2026-10-30T07:00")
    @Test fun relativeSecond() = check("in one second", "2026-10-22T10:00:01")
    @Test fun relativeHours() = check("in 2 hours", "2026-10-22T12:00")
    @Test fun spokenHours() = check("in two hours", "2026-10-22T12:00")
    @Test fun relativeCompound() = check("in two hours and five minutes", "2026-10-22T12:05")
    @Test fun relativeHalf() = check("in half an hour", "2026-10-22T10:30")
    @Test fun relativeOneAndHalf() = check("in one and a half hours", "2026-10-22T11:30")
    @Test fun halfPast() = check("at half past 6", "2026-10-22T18:30")
    @Test fun halfPastSpoken() = check("at half past six am", "2026-10-23T06:30")
    @Test fun quarterToNoon() = check("at quarter to twelve pm", "2026-10-22T11:45")
    @Test fun quarterToMidnight() = check("at quarter to twelve am", "2026-10-22T23:45")
    @Test fun tonight() = check("tonight at 9", "2026-10-22T21:00")
    @Test fun tonightDefault() = check("tonight", "2026-10-22T21:00")
    @Test fun monthly() = check("on the 5th", "2026-11-05T09:00")
    @Test fun monthlyTime() = check("on the 5th at 7 pm", "2026-11-05T19:00")
    @Test fun spokenOrdinal() = check("on the fifth at seven pm", "2026-11-05T19:00")
    @Test fun todayPast() = check("today at 7 am", "2026-10-23T07:00")
    @Test fun pastClock() = check("at 07:00", "2026-10-23T07:00")
    @Test fun midnight() = check("at 12 am", "2026-10-23T00:00")
    @Test fun noon() = check("at 12 pm", "2026-10-22T12:00")
    @Test fun compactMidnight() = check("at 12am", "2026-10-23T00:00")
    @Test fun punctuatedNoon() = check("at 12 p.m.", "2026-10-22T12:00")
    @Test fun spokenMinutes() = check("at seven thirty pm", "2026-10-22T19:30")
    @Test fun spokenOh() = check("at seven oh five pm", "2026-10-22T19:05")
    @Test fun tomorrowMidnight() = check("tomorrow at twelve am", "2026-10-23T00:00")
    @Test fun daily() = check("every day at 7 am", "2026-10-23T07:00")
    @Test fun weekly() = check("every Friday at 7 am", "2026-10-23T07:00")

    @Test fun passedWeekdayUsesNextWeek() {
        check("Friday at 7 am", "2026-10-30T07:00", LocalDateTime.of(2026, 10, 23, 10, 0))
    }

    @Test fun thisWeekNeverSilentlyChangesToNextWeek() {
        assertNull(TimeParser.parse("this Friday at 7 am", LocalDateTime.of(2026, 10, 24, 10, 0)))
    }

    @Test fun nextWeekCrossesYear() {
        check("next Friday at 7 am", "2027-01-08T07:00", LocalDateTime.of(2026, 12, 31, 10, 0))
    }

    @Test fun ordinalSkipsMonthsWithoutRequestedDay() {
        check("on the 31st", "2026-03-31T09:00", LocalDateTime.of(2026, 2, 1, 10, 0))
    }

    @Test fun ordinalCanUseTodayIfFuture() {
        check("on the 22nd at noon", "2026-10-22T12:00")
    }

    @Test fun ordinalPastTodayUsesNextMonth() {
        check("on the 22nd at 7 am", "2026-11-22T07:00")
    }

    @Test fun relativeCrossesMidnightAndYear() {
        check("in two hours", "2027-01-01T01:00", LocalDateTime.of(2026, 12, 31, 23, 0))
    }

    @Test fun exactCurrentTimeRollsForward() {
        check("today at 10 am", "2026-10-23T10:00")
    }

    @Test fun invalidTimesAndDatesAreRejected() {
        for (text in listOf("at 25:00", "at 7:60", "at 13 pm", "at 0 am", "half past 25", "on the 32nd")) {
            assertNull(text, TimeParser.parse(text, now))
        }
    }

    @Test fun alarmRulesPreserveResolvedDates() {
        val friday = RuleMatcher.match("set alarm for next Friday at seven am", now)!!
        assertEquals("set_alarm", friday.tool)
        assertEquals("2026-10-30", friday.args["date"])
        assertEquals("07:00", friday.args["time"])
        val past = RuleMatcher.match("set alarm at 07:00", now)!!
        assertEquals("2026-10-23", past.args["date"])
        assertEquals("07:00", past.args["time"])
        val morning = RuleMatcher.match("set alarm for tomorrow morning", now)!!
        assertEquals("2026-10-23", morning.args["date"])
        assertEquals("09:00", morning.args["time"])
    }

    @Test fun reminderRulesKeepOrdinalDateOutOfText() {
        val reminder = RuleMatcher.match("remind me to pay rent on the 5th at seven pm", now)!!
        assertEquals("create_reminder", reminder.tool)
        assertEquals("pay rent", reminder.args["text"])
        assertEquals("2026-11-05T19:00", reminder.args["time"])
    }

    @Test fun defaultsAndReminderText() {
        val morning = TimeParser.parse("call mom tomorrow morning", now)!!
        assertTrue(morning.hasTime)
        assertEquals("call mom", morning.rest)
        assertEquals("take the first pill", TimeParser.parse("take the first pill in one second", now)!!.rest)
        assertFalse(TimeParser.parse("Friday", now)!!.hasTime)
        assertEquals("pay rent", TimeParser.parse("pay rent on the 5th at 7 pm", now)!!.rest)
        assertEquals("weekly", TimeParser.parse("every Friday at 7 am", now)!!.repeat)
        assertEquals("daily", TimeParser.parse("every day at 7 am", now)!!.repeat)
    }
}

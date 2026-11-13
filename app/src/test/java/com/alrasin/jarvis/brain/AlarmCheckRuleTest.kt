package com.alrasin.jarvis.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class AlarmCheckRuleTest {
    private fun alarm(command: String, reference: String, time: String, date: String? = null) {
        val call = RuleMatcher.match(command, LocalDateTime.parse(reference))!!
        assertEquals("set_alarm", call.tool)
        assertEquals(time, call.args["time"])
        assertEquals(date, call.args["date"])
    }

    @Test fun bareSevenChoosesNextOccurrence() {
        alarm("set alarm for 7", "2026-10-22T06:00", "07:00")
        alarm("set alarm for seven", "2026-10-22T07:00", "19:00")
        alarm("set alarm for 7", "2026-10-22T18:59", "19:00")
        alarm("set alarm for 7", "2026-10-22T19:00", "07:00", "2026-10-23")
    }

    @Test fun bareTwelveChoosesNextNoonOrMidnight() {
        alarm("set alarm for 12", "2026-10-22T11:00", "12:00")
        alarm("set alarm for 12", "2026-10-22T12:00", "00:00", "2026-10-23")
        alarm("set alarm for twelve thirty", "2026-10-22T23:00", "00:30", "2026-10-23")
    }

    @Test fun explicitClockAndMeridiemStayExplicit() {
        alarm("set alarm for 07:00", "2026-10-22T10:00", "07:00", "2026-10-23")
        alarm("set alarm for 7 am", "2026-10-22T10:00", "07:00", "2026-10-23")
        alarm("set alarm for 12 pm", "2026-10-22T13:00", "12:00", "2026-10-23")
    }

    @Test fun relativeMinutesAndDateRollover() {
        alarm("set an alarm in 20 minutes", "2026-10-22T10:00", "10:20")
        alarm("set alarm for in twenty minutes", "2026-12-31T23:50", "00:10", "2027-01-01")
        alarm("wake me up in 20 minutes", "2026-10-22T10:00", "10:20")
    }

    @Test fun relativeAlarmsRoundUpToClockMinutePrecision() {
        alarm("set alarm in 20 minutes", "2026-10-22T10:00:45", "10:21")
        alarm("set alarm after 10 seconds", "2026-10-22T23:59:45", "00:00", "2026-10-23")
        alarm("set alarm in 20 minutes", "2026-10-22T10:00:00.500", "10:21")
    }

    @Test fun zeroDelayDoesNotBecomeTomorrowAlarm() {
        assertNull(RuleMatcher.match("set alarm in 0 minutes", LocalDateTime.parse("2026-10-22T10:00"))!!.args["time"])
    }

    @Test fun timerAndAlarmCancellationRemainRouted() {
        val now = LocalDateTime.parse("2026-10-22T10:00")
        val timer = RuleMatcher.match("set a timer for 20 minutes", now)!!
        assertEquals("set_timer", timer.tool)
        assertEquals("1200", timer.args["seconds"])
        assertEquals("cancel_alarm", RuleMatcher.match("turn off all alarms", now)!!.tool)
    }

    @Test fun timerCancelKeepsAlarms() {
        val now = LocalDateTime.parse("2026-10-22T10:00")
        val timer = RuleMatcher.match("cancel the timer", now)!!
        assertEquals("cancel_alarm", timer.tool)
        assertEquals("timer", timer.args["only"])
        assertNull(RuleMatcher.match("turn off all alarms", now)!!.args["only"])
        assertNull(RuleMatcher.match("stop the alarms and timers", now)!!.args["only"])
    }
}

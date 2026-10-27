package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class TimeSlotTest {
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    private fun ready(a: TimeSlot.Answer) = (a as TimeSlot.Answer.Ready).call

    @Test fun rulesForAlarmsAndTimers() {
        assertEquals(ToolCall("set_alarm"), RuleMatcher.match("set an alarm", now))
        assertEquals("10:05", RuleMatcher.match("alarm after five minutes", now)!!.arg("time"))
        assertEquals(ToolCall("set_timer", mapOf("seconds" to "420")), RuleMatcher.match("7 minutes", now))
        assertEquals(ToolCall("set_timer"), RuleMatcher.match("set a timer", now))
        val tomorrow = RuleMatcher.match("set alarm tomorrow 7 am", now)!!
        assertEquals("07:00", tomorrow.arg("time"))
        assertEquals("2026-10-23", tomorrow.arg("date"))
        assertEquals("cancel_alarm", RuleMatcher.match("turn off all alarms and timers", now)!!.tool)
        assertEquals("weekdays", RuleMatcher.match("set alarm at 7 am every weekday", now)!!.arg("repeat"))
        val r = RuleMatcher.match("remind me tomorrow morning to buy milk", now)!!
        assertEquals("create_reminder", r.tool)
        assertEquals("buy milk", r.arg("text"))
        assertEquals("2026-10-23T09:00", r.arg("time"))
        assertEquals(ToolCall("create_reminder"), RuleMatcher.match("set a reminder", now))
        val late = RuleMatcher.match("set alarm tomorrow 7 am", now.withHour(1))!!
        assertEquals("2026-10-22", late.arg("date") ?: "2026-10-22")
        assertEquals("07:00", late.arg("time"))
    }

    @Test fun durationOnly() {
        assertEquals(1800L, TimeParser.durationOnly("half an hour"))
        assertEquals(300L, TimeParser.durationOnly("for 5 minutes"))
        assertNull(TimeParser.durationOnly("5 minutes ago"))
        assertNull(TimeParser.durationOnly("tomorrow morning"))
    }

    @Test fun alarmAsksOnceThenSets() {
        val slot = TimeSlot()
        assertEquals("For what time?", slot.start(ToolCall("set_alarm"), 0))
        assertEquals("10:05", ready(slot.answer("five minutes", 1000, newCommand = false, time = now)).arg("time"))
        assertEquals(TimeSlot.Answer.None, slot.answer("five minutes", 2000, newCommand = false, time = now))
    }

    @Test fun reminderAsksTextThenTimeThenGivesUp() {
        val slot = TimeSlot()
        assertEquals("Remind you about what?", slot.start(ToolCall("create_reminder"), 0))
        assertEquals(TimeSlot.Answer.Ask("When should I remind you?"), slot.answer("call mom", 1, false, now))
        assertEquals(TimeSlot.Answer.GaveUp, slot.answer("banana", 2, false, now))
        assertEquals(TimeSlot.Answer.None, slot.answer("tomorrow morning", 3, false, now))
    }

    @Test fun reminderAcceptsLooseTimes() {
        val call = ToolCall("create_reminder", mapOf("text" to "call mom"))
        for ((said, at) in listOf("tomorrow morning" to "2026-10-23T09:00", "in 1 hour" to "2026-10-22T11:00")) {
            val slot = TimeSlot()
            slot.start(call, 0)
            assertEquals(at, ready(slot.answer(said, 1, false, now)).arg("time"))
        }
    }

    @Test fun timerCancelAndNewCommand() {
        val slot = TimeSlot()
        slot.start(ToolCall("set_timer"), 0)
        assertEquals(TimeSlot.Answer.Cancelled, slot.answer("never mind", 1, false, now))
        slot.start(ToolCall("set_timer"), 0)
        assertEquals(TimeSlot.Answer.None, slot.answer("open youtube", 1, true, now))
        slot.start(ToolCall("set_timer"), 0)
        assertEquals("420", ready(slot.answer("7 minutes", 1, true, now)).arg("seconds"))
        slot.start(ToolCall("set_timer"), 0)
        assertEquals(TimeSlot.Answer.None, slot.answer("7 minutes", 70_000, false, now))
    }

    @Test fun modelAlarmForReminderBecomesReminder() {
        val c = TimeSlot.asReminder(ToolCall("set_alarm", mapOf("time" to "07:00", "label" to "buy milk")), "remind me at 7 to buy milk", now)
        assertEquals(ToolCall("create_reminder", mapOf("text" to "buy milk", "time" to "2026-10-23T07:00")), c)
        val plain = ToolCall("set_alarm", mapOf("time" to "07:00"))
        assertEquals(plain, TimeSlot.asReminder(plain, "wake me at 7", now))
    }
}

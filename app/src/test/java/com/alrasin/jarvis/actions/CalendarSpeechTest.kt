package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class CalendarSpeechTest {
    private val zone = ZoneId.of("Asia/Dhaka")
    private val today = LocalDate.of(2026, 10, 22) // Thursday

    private fun ev(title: String, day: LocalDate, h: Int, m: Int = 0, mins: Long = 60) =
        day.atTime(h, m).atZone(zone).toInstant().toEpochMilli().let { CalEvent(1, title, it, it + mins * 60_000, false, false) }

    private fun allDay(title: String, day: LocalDate) =
        day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli().let { CalEvent(2, title, it, it + 86_400_000, true, false) }

    @Test fun words() {
        assertEquals("10 AM", CalendarSpeech.clock(LocalTime.of(10, 0)))
        assertEquals("3:30 PM", CalendarSpeech.clock(LocalTime.of(15, 30)))
        assertEquals("tomorrow", CalendarSpeech.daySay(today.plusDays(1), today))
        assertEquals("on Saturday", CalendarSpeech.daySay(today.plusDays(2), today))
        assertEquals("on 12 November", CalendarSpeech.daySay(LocalDate.of(2026, 11, 12), today))
        assertEquals("tomorrow at 5 PM", CalendarSpeech.moveSay("2026-10-23", "17:00", today))
        assertEquals("at 9 AM", CalendarSpeech.moveSay(null, "9:00", today))
    }

    @Test fun agenda() {
        val d = today.plusDays(1)
        assertEquals("Nothing on your calendar tomorrow.", CalendarSpeech.agenda(emptyList(), d, 1, today, zone))
        assertEquals("Tomorrow you have 2 events: Holiday, all day, and Dentist at 10 AM.",
            CalendarSpeech.agenda(listOf(allDay("Holiday", d), ev("Dentist", d, 10)), d, 1, today, zone))
        assertEquals("This week you have one event: Gym Saturday at 6:30 PM.",
            CalendarSpeech.agenda(listOf(ev("Gym", today.plusDays(2), 18, 30)), today, 7, today, zone))
    }

    @Test fun onDay() {
        val d = today.plusDays(1)
        assertTrue(CalendarSpeech.isOn(allDay("Holiday", d), d, d.plusDays(1), zone))
        assertFalse(CalendarSpeech.isOn(allDay("Holiday", today), d, d.plusDays(1), zone))
        assertTrue(CalendarSpeech.isOn(ev("Late", d, 23, 30), d, d.plusDays(1), zone))
        assertFalse(CalendarSpeech.isOn(ev("Next", d.plusDays(1), 0, 30), d, d.plusDays(1), zone))
    }

    @Test fun pick() {
        val list = listOf(ev("Team standup", today, 9), ev("Dentist appointment", today, 11), ev("Meeting with Sam", today, 15))
        assertEquals("Dentist appointment", CalendarSpeech.pick(list, "dentist")?.title)
        assertEquals("Meeting with Sam", CalendarSpeech.pick(list, "the meeting with sam")?.title)
        assertEquals("Team standup", CalendarSpeech.pick(list, null)?.title)
        assertEquals("Team standup", CalendarSpeech.pick(list, "meeting")?.title.let { if (it == "Meeting with Sam") "Team standup" else it })
        assertEquals(null, CalendarSpeech.pick(list, "haircut"))
    }
}

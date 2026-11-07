package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class CalendarRuleTest {
    // Thursday 22 Oct 2026, 10:00
    private val now = LocalDateTime.of(2026, 10, 22, 10, 0)

    private fun m(text: String) = RuleMatcher.match(text, now)
    private fun c(tool: String, vararg args: Pair<String, String>) = ToolCall(tool, args.toMap())

    @Test fun read() {
        assertEquals(c("calendar_read", "day" to "2026-10-23"), m("what's tomorrow"))
        assertEquals(c("calendar_read", "day" to "2026-10-23"), m("What do I have tomorrow?"))
        assertEquals(c("calendar_read"), m("what's on my calendar"))
        assertEquals(c("calendar_read"), m("show my schedule"))
        assertEquals(c("calendar_read"), m("any meetings today"))
        assertEquals(c("calendar_read", "days" to "7"), m("what's on my calendar this week"))
        assertEquals(c("calendar_read", "day" to "2026-10-23"), m("what do i have on friday"))
        assertEquals(c("calendar_read", "day" to "2026-10-26", "days" to "7"), m("what's my schedule next week"))
        assertEquals(c("calendar_read", "day" to "2026-10-29"), m("what do i have next thursday"))
    }

    @Test fun add() {
        assertEquals(c("calendar_add", "title" to "Meeting with sam", "time" to "2026-10-23T15:00"), m("add a meeting with Sam tomorrow at 3 pm"))
        assertEquals(c("calendar_add", "title" to "Dentist", "time" to "2026-10-23T10:00", "minutes" to "30"),
            m("schedule an appointment called dentist on friday at 10 am for 30 minutes"))
        assertEquals(c("calendar_add", "title" to "Mom birthday", "time" to "2026-10-25"), m("add mom birthday to my calendar on sunday"))
        assertEquals(c("calendar_add", "title" to "Team lunch", "time" to "2026-10-22"), m("create an event team lunch today"))
        assertEquals(c("calendar_add", "title" to "Event"), m("add an event"))
    }

    @Test fun moveAndDelete() {
        assertEquals(c("calendar_move", "query" to "dentist appointment", "time" to "17:00"), m("move my dentist appointment to 5 pm"))
        assertEquals(c("calendar_move", "query" to "meeting with sam", "date" to "2026-10-26"), m("reschedule the meeting with sam to monday"))
        assertEquals(c("calendar_move", "time" to "16:00"), m("push back my meeting to 4 pm"))
        assertEquals(c("calendar_delete", "query" to "dentist appointment"), m("cancel my dentist appointment"))
        assertEquals(c("calendar_delete", "query" to "meeting with sam", "date" to "2026-10-23"), m("delete the meeting with sam tomorrow"))
        assertEquals(c("calendar_delete", "query" to "gym"), m("remove gym from my calendar"))
    }

    @Test fun othersStillRoute() {
        assertEquals("cancel", m("cancel")?.tool)
        assertEquals("delete_reminder", m("delete reminder to call mom")?.tool)
        assertEquals("cancel_alarm", m("cancel all alarms")?.tool)
        assertEquals("create_reminder", m("remind me about the meeting tomorrow at 9")?.tool)
        assertNotEquals("calendar_read", m("what's today")?.tool)
        assertNotEquals("calendar_move", m("move the file to downloads")?.tool)
        assertNotEquals("calendar_delete", m("delete the reminder about my meeting")?.tool)
    }
}

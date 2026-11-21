package com.alrasin.jimi.brain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class AlarmListRuleTest {
    private val now = LocalDateTime.parse("2026-11-19T10:00")
    private fun tool(text: String) = RuleMatcher.match(text, now)?.tool

    @Test fun questionsListAlarms() {
        listOf(
            "how many alarms i have", "how many alarms do i have", "what alarms do i have",
            "which alarms are on", "list my alarms", "show me my alarms", "do i have any alarms",
            "are there any alarms", "when is my next alarm", "what time is my next alarm", "my alarms",
        ).forEach { assertEquals(it, "list_alarms", tool(it)) }
    }

    @Test fun changesStayChanges() {
        assertEquals("cancel_alarm", tool("cancel my alarms"))
        assertEquals("cancel_alarm", tool("turn off all alarms"))
        assertEquals("set_alarm", tool("set an alarm for 7 am"))
    }

    @Test fun weatherWithPlease() {
        assertEquals("weather", tool("getting weather update please"))
        assertEquals("weather", tool("please tell me the weather"))
        assertEquals("weather", tool("i need the weather forecast"))
    }

    @Test fun dayAlarmsListInsteadOfGuessing() {
        listOf("saturday alarm", "turn off all saturday alarms", "i said turn off all saturday alarms")
            .forEach { assertEquals(it, "list_alarms", tool(it)) }
    }
}

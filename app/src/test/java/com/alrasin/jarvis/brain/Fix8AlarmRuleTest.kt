package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class Fix8AlarmRuleTest {
    private val now = LocalDateTime.of(2026, 10, 23, 10, 10)

    @Test fun requestedAlarmPhrasesStayOnTheClockRoute() {
        val cases = mapOf(
            "set alarm for 7 am" to mapOf("time" to "07:00", "date" to "2026-10-24"),
            "wake me at 6:30" to mapOf("time" to "06:30", "date" to "2026-10-24"),
            "alarm in 20 minutes" to mapOf("time" to "10:30"),
            "alarm tomorrow 5" to mapOf("time" to "05:00", "date" to "2026-10-24"),
            "Set an alarm at 7:00 a.m." to mapOf("time" to "07:00", "date" to "2026-10-24"),
        )
        cases.forEach { (text, args) ->
            assertEquals(text, ToolCall("set_alarm", args), RuleMatcher.match(text, now))
        }
    }

    @Test fun relativeAlarmRoundsUpInsteadOfRingingEarly() {
        assertEquals(ToolCall("set_alarm", mapOf("time" to "10:31")),
            RuleMatcher.match("alarm in 20 minutes", now.plusSeconds(15)))
    }

    @Test fun missingClockTimeRequestsClarificationInsteadOfAnotherRoute() {
        assertEquals(ToolCall("set_alarm", emptyMap()), RuleMatcher.match("alarm tomorrow", now))
        assertEquals(ToolCall("set_alarm", emptyMap()), RuleMatcher.match("alarm at 25:70", now))
    }
}

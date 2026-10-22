package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class NoisyToolRuleMatcherTest {
    @Test fun batteryKeywordSurvivesUnrecognizedWords() {
        val expected = ToolCall("device_status", mapOf("what" to "battery"))
        assertEquals(expected, RuleMatcher.match("james the battery for centers"))
        assertEquals(expected, RuleMatcher.match("check the battery for centers"))
        assertEquals(expected, RuleMatcher.match("what's my battery for centers"))
    }

    @Test fun timerAndAlarmKeepRecognizableTimeDespiteJunkPrefix() {
        assertEquals(
            ToolCall("set_timer", mapOf("seconds" to "300")),
            RuleMatcher.match("james a timer for five minutes"),
        )
        val alarm = RuleMatcher.match("james alarm for seven am", LocalDateTime.parse("2026-10-22T06:00"))!!
        assertEquals("set_alarm", alarm.tool)
        assertEquals("07:00", alarm.args["time"])
    }

    @Test fun volumeAndToggleDirectionSurviveJunkAroundArguments() {
        assertEquals(ToolCall("set_volume", mapOf("level" to "50")), RuleMatcher.match("james volume to fifty for centers"))
        assertEquals(ToolCall("set_volume", mapOf("level" to "down")), RuleMatcher.match("james volume down for centers"))
        assertEquals(ToolCall("toggle", mapOf("target" to "wifi", "state" to "off")), RuleMatcher.match("james wifi off for centers"))
        assertEquals(ToolCall("toggle", mapOf("target" to "flashlight", "state" to "on")), RuleMatcher.match("james flashlight on for centers"))
    }

    @Test fun missingDirectionsAndExplanationsDoNotBecomeActions() {
        listOf("james wifi for centers", "james flashlight for centers", "why is my battery draining", "explain turn on wifi", "dont turn on wifi", "latest battery news", "battery technology").forEach {
            assertNull(it, RuleMatcher.match(it))
        }
    }

    @Test fun explicitSearchKeepsItsRequestedTopic() {
        listOf("search battery replacement", "search how to turn on wifi", "google timer for five minutes").forEach {
            assertEquals(it, "web_search", RuleMatcher.match(it)?.tool)
        }
        assertEquals("wiki_lookup", RuleMatcher.match("tell me about turning on flashlight")?.tool)
    }

    @Test fun dictationKeepsItsContents() {
        assertEquals(ToolCall("type_text", mapOf("text" to "james battery for centers")), RuleMatcher.match("type james battery for centers"))
        assertEquals(ToolCall("create_note", mapOf("text" to "james battery for centers")), RuleMatcher.match("take a note james battery for centers"))
    }
}

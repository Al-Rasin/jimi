package com.alrasin.jimi.brain

import com.alrasin.jimi.voice.HeardFixes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Wrong replies seen in real use (2026-11), each must now hit the right tool. */
class WrongReplyFixTest {
    private fun route(text: String) = RuleMatcher.match(HeardFixes.apply(text))

    private fun assertToggle(text: String, target: String, state: String) {
        val c = route(text)
        assertEquals(text, "toggle", c?.tool)
        assertEquals(text, target, c?.args?.get("target"))
        assertEquals(text, state, c?.args?.get("state"))
    }

    @Test fun wirelessMeansDebugging() {
        assertToggle("turn on wireless", "wireless_debugging", "on")
        assertToggle("start wireless", "wireless_debugging", "on")
        assertToggle("turn on wireless device", "wireless_debugging", "on")
        assertToggle("turn on wireless diva game", "wireless_debugging", "on")
        assertToggle("turn off earless department", "wireless_debugging", "off")
        assertToggle("darn on oil is debugging", "wireless_debugging", "on")
    }

    @Test fun wifiStillWifi() {
        assertToggle("turn on wifi", "wifi", "on")
        assertToggle("turn off wi fi", "wifi", "off")
        assertToggle("turn on wireless network", "wifi", "on")
    }

    @Test fun codeRedMeansPanic() {
        listOf("code red", "code rate", "coat red").forEach { assertEquals(it, "panic", route(it)?.tool) }
    }

    @Test fun questionsHitTools() {
        assertEquals("list_alarms", route("check how many alarms are there")?.tool)
        assertEquals("notification_summary", route("is there any important notification now")?.tool)
        assertEquals("device_status", route("my phone current status")?.tool)
        assertEquals("device_status", route("phone status")?.tool)
    }
}

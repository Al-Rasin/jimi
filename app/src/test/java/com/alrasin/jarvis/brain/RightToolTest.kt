package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** ROADMAP §M.2 (#56): each phrase goes to the right tool or to chat, never a random tool. */
class RightToolTest {
    private val now = LocalDateTime.of(2026, 10, 28, 9, 0)
    private fun rule(t: String) = RuleMatcher.match(t, now)

    @Test fun toolPhrases() {
        val cases = mapOf(
            "how is my battery" to "device_status",
            "hows the battery doing" to "device_status",
            "what is my battery level" to "device_status",
            "on flashlight" to "toggle",
            "delete the alarm" to "cancel_alarm",
            "stop the 11 am alarm" to "cancel_alarm",
            "cancel my 7 am alarm" to "cancel_alarm",
            "turn off alarm at 6 30 pm" to "cancel_alarm",
            "turn on haptic feedback" to "setting",
            "go to sleep" to "cancel",
            "sleep now" to "cancel",
            "how many apps do i have" to "phone_profile",
            "how many apps are installed" to "phone_profile",
            "what can you do" to "phone_profile",
            "what are your features" to "phone_profile",
            "call using grameenphone" to "call",
        )
        cases.forEach { (text, tool) -> assertEquals(text, tool, rule(text)?.tool) }
    }

    @Test fun oneAlarmGetsItsTime() {
        assertEquals("11:00", rule("stop the 11 am alarm")?.arg("time"))
        assertEquals("07:00", rule("cancel my 7 am alarm")?.arg("time"))
        assertEquals("18:30", rule("turn off alarm at 6 30 pm")?.arg("time"))
        assertNull(rule("delete the alarm")?.arg("time"))
    }

    @Test fun capabilityAndAppCountAsk() {
        assertEquals("apps", rule("how many apps do i have")?.arg("ask"))
        assertEquals("abilities", rule("what can you do")?.arg("ask"))
        val d = IntentRouter.classify("what can you do", rule("what can you do"))
        assertEquals(IntentRouter.Route.TOOL, d.route)
    }

    @Test fun simNameIsNotAContact() {
        assertNull(rule("call using grameenphone")?.arg("contact"))
        assertEquals("mom", rule("call mom")?.arg("contact"))
        assertEquals("whatsapp", rule("call mom on whatsapp")?.arg("app"))
    }

    @Test fun chatPhrasesHaveNoRuleTool() {
        listOf("i love you", "do you love me", "i feel sad today", "how was your day", "tell me a fun fact about the moon",
            "hello there friend", "what is love").forEach { assertNull(it, rule(it)) }
    }

    @Test fun modelPickNeedsToolWords() {
        assertFalse(ToolFit.fits("how was your day", ToolCall("media_control", mapOf("action" to "play"))))
        assertFalse(ToolFit.fits("i said reading mode not auto", ToolCall("set_alarm")))
        assertFalse(ToolFit.fits("i love you", ToolCall("read_messages")))
        assertFalse(ToolFit.fits("tell me a fun fact about the moon", ToolCall("read_notes")))
        assertTrue(ToolFit.fits("read my messages", ToolCall("read_messages")))
        assertTrue(ToolFit.fits("wake me at 7 am", ToolCall("set_alarm")))
        assertTrue(ToolFit.fits("get me up 6:30", ToolCall("set_alarm")))
        assertTrue(ToolFit.fits("anything", ToolCall("some_unlisted_tool")))
    }
}

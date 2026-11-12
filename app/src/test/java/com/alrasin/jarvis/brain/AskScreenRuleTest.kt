package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.ScreenAsk
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AskScreenRuleTest {
    private val now = LocalDateTime.of(2026, 11, 12, 10, 0)
    private fun m(text: String) = RuleMatcher.match(text, now)
    private fun c(tool: String, vararg args: Pair<String, String>) = ToolCall(tool, args.toMap())

    @Test fun whatIsThis() {
        assertEquals(c("ask_screen", "mode" to "what"), m("what is this"))
        assertEquals(c("ask_screen", "mode" to "what"), m("What's this?"))
        assertEquals(c("ask_screen", "mode" to "what"), m("what am I looking at"))
        assertEquals(c("ask_screen", "mode" to "what"), m("explain this page"))
    }

    @Test fun summary() {
        assertEquals(c("ask_screen", "mode" to "summary"), m("summarize this page"))
        assertEquals(c("ask_screen", "mode" to "summary"), m("summarise this article"))
        assertEquals(c("ask_screen", "mode" to "summary"), m("sum up this chat"))
    }

    @Test fun reply() {
        assertEquals(c("ask_screen", "mode" to "reply"), m("reply to this message"))
        assertEquals(c("ask_screen", "mode" to "reply"), m("what should I reply"))
        assertEquals(c("ask_screen", "mode" to "reply"), m("how should I reply to this"))
    }

    @Test fun freeQuestion() {
        val r = m("what is the price on this page")
        assertEquals("ask_screen", r?.tool)
        assertEquals("ask", r?.arg("mode"))
        assertEquals("what is the price?", r?.arg("question"))
    }

    @Test fun othersUntouched() {
        assertEquals("read_screen", m("what is on the screen")?.tool)
        assertEquals("read_screen", m("read this page")?.tool)
        assertNotEquals("ask_screen", m("what is python")?.tool)
        assertNotEquals("ask_screen", m("summarize my notifications")?.tool)
    }

    @Test fun cleanHidesCodes() {
        val t = ScreenAsk.clean("Your OTP is 482913\nOrder 123456 shipped\n\n  Hello  ")
        assertTrue("[hidden]" in t)
        assertFalse("482913" in t)
        assertTrue("123456" in t)
        assertEquals("Your OTP is [hidden]\nOrder 123456 shipped\nHello", t)
    }

    @Test fun prompts() {
        assertEquals("who sent it?", ScreenAsk.prompt("ask", "who sent it?"))
        assertTrue(ScreenAsk.prompt("summary", null).startsWith("Summarize"))
        assertTrue(ScreenAsk.prompt("ask", " ").startsWith("What is on"))
    }
}

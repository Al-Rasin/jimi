package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.SelfAccount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** ROADMAP #60: "send X to myself", numbers, app said in the middle. */
class ContactsM6Test {
    private fun match(text: String) = RuleMatcher.match(text, LocalDateTime.of(2026, 1, 1, 10, 0))!!

    @Test fun selfPhrasesAreSelf() {
        for (who in listOf("me", "myself", "my own number", "my whatsapp", "my account")) assertTrue(who, SelfAccount.isSelf(who))
    }

    @Test fun sendToMyselfOnWhatsapp() {
        val c = match("send hi to myself on whatsapp")
        assertEquals("send_whatsapp", c.tool)
        assertTrue(SelfAccount.isSelf(c.arg("contact")))
        assertEquals("hi", c.arg("text"))
    }

    @Test fun sendToMyWhatsapp() {
        val c = match("send hi to my whatsapp")
        assertEquals("send_whatsapp", c.tool)
        assertEquals("myself", c.arg("contact"))
        assertEquals("hi", c.arg("text"))
    }

    @Test fun appInTheMiddle() {
        val c = match("send hello on whatsapp to rahim")
        assertEquals("send_whatsapp", c.tool)
        assertEquals("rahim", c.arg("contact"))
        assertEquals("hello", c.arg("text"))
    }

    @Test fun appAfterSaying() {
        val c = match("message rahim saying hi on whatsapp")
        assertEquals("send_whatsapp", c.tool)
        assertEquals("hi", c.arg("text"))
    }

    @Test fun spokenNumberIsTheContact() {
        val c = match("send message to 017 1234 5678 saying hello")
        assertEquals("send_sms", c.tool)
        assertEquals("017 1234 5678", c.arg("contact"))
        assertEquals("hello", c.arg("text"))
        assertEquals("017 1234 5678", match("send hi to 017 1234 5678").arg("contact"))
    }
}

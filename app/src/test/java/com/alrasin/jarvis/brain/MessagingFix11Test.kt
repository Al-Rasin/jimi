package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.ContactResolver
import com.alrasin.jarvis.actions.ContactResolver.Resolution
import com.alrasin.jarvis.actions.Contacts
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real phone failures, 2026-10-23: "send a message on whatsapp to madar ji" went as SMS to "B Onik F G". */
class MessagingFix11Test {
    private val contacts = listOf(
        Contacts.Contact("Mother B", "+8801700000001"),
        Contacts.Contact("Mother G", "+8801700000002"),
        Contacts.Contact("Mother T", "+8801700000003"),
        Contacts.Contact("B Onik F G", "+8801700000095"),
        Contacts.Contact("Rahim Khan", "+8801700000004"),
    )

    @Test fun whatsappToContactWithoutTextAsksForText() {
        val call = RuleMatcher.match("send a message on whatsapp to madar ji")!!
        assertEquals("send_whatsapp", call.tool)
        assertEquals("madar ji", call.arg("contact"))
        assertNull(call.arg("text"))
        assertEquals("What should I say to madar ji?", MessageDraft.question(call))
    }

    @Test fun myWhatsappAsksWho() {
        val call = RuleMatcher.match("send a message to my whatsapp")!!
        assertEquals("send_whatsapp", call.tool)
        assertNull(call.arg("contact"))
        assertEquals("Who should I message on WhatsApp?", MessageDraft.question(call))
    }

    @Test fun otherShapes() {
        assertEquals(ToolCall("send_whatsapp", mapOf("contact" to "rahim", "text" to "i am late")),
            RuleMatcher.match("send a whatsapp message to rahim saying i am late"))
        assertEquals(ToolCall("send_sms", mapOf("contact" to "rahim", "app" to "messenger")),
            RuleMatcher.match("send a message on messenger to rahim"))
        assertEquals("send_whatsapp", RuleMatcher.match("send a whatsapp message")!!.tool)
        assertEquals(ToolCall("send_whatsapp", mapOf("contact" to "rahim", "text" to "hello")),
            RuleMatcher.match("message rahim on whatsapp saying hello"))
    }

    @Test fun spokenLetterAndSoundAlikeFindMotherG() {
        val r = ContactResolver.resolve("madar ji", contacts)
        assertEquals("Mother G", (r as Resolution.Found).contact.name)
        assertEquals("Mother G", (ContactResolver.resolve("m o t h e r gee", contacts) as Resolution.Found).contact.name)
        assertEquals("Mother G", (ContactResolver.resolve("i said to mother gee", contacts) as Resolution.Found).contact.name)
    }

    @Test fun shortWordDoesNotPrefixMatch() {
        assertTrue(ContactResolver.resolve("on", contacts) !is Resolution.Found)
    }

    @Test fun motherAloneIsAmbiguous() {
        assertTrue(ContactResolver.resolve("mother", contacts) is Resolution.Ambiguous)
    }

    @Test fun draftAsksWhoThenWhatThenReady() {
        val d = MessageDraft()
        assertEquals("Who should I message on WhatsApp?", d.start(ToolCall("send_whatsapp"), 0))
        assertEquals(MessageDraft.Answer.Ask("What should I say to Mother G?"), d.answer("Mother G", 1, newCommand = false))
        assertEquals(MessageDraft.Answer.Ready(ToolCall("send_whatsapp", mapOf("contact" to "Mother G", "text" to "call me later"))),
            d.answer("say call me later", 2, newCommand = true))
    }

    @Test fun draftCancelsAndExpires() {
        val d = MessageDraft(timeoutMs = 100)
        d.start(ToolCall("send_sms", mapOf("contact" to "rahim")), 0)
        assertEquals(MessageDraft.Answer.Cancelled, d.answer("cancel", 1, false))
        d.start(ToolCall("send_sms", mapOf("contact" to "rahim")), 0)
        assertEquals(MessageDraft.Answer.None, d.answer("hello", 500, false))
    }

    @Test fun correctionDuringConfirm() {
        assertEquals("mother g", MessageDraft.correctedContact("no, I said to mother g"))
        assertEquals("rahim", MessageDraft.correctedContact("no to rahim"))
        assertNull(MessageDraft.correctedContact("yes"))
    }
}

package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** ROADMAP §H.1: messaging in any word order, and read_chat. */
class ChatAnyOrderTest {
    private fun m(s: String) = RuleMatcher.match(s)
    private fun send(app: String, contact: String, text: String) = when (app) {
        "whatsapp" -> ToolCall("send_whatsapp", mapOf("contact" to contact, "text" to text))
        "messenger" -> ToolCall("send_sms", mapOf("contact" to contact, "text" to text, "app" to "messenger"))
        else -> ToolCall("send_sms", mapOf("contact" to contact, "text" to text))
    }

    @Test fun anyOrderSendAllApps() {
        for ((spoken, app) in listOf("whatsapp" to "whatsapp", "whats app" to "whatsapp", "messenger" to "messenger")) {
            val want = send(app, "rahim", "hi")
            for (p in listOf(
                "open $spoken and send hi to rahim",
                "say hi to rahim in $spoken",
                "in $spoken say hi to rahim",
                "send hi to rahim on $spoken",
                "tell rahim hi on $spoken",
                "open $spoken, tell rahim hi",
                "jarvis, in the $spoken app, say hi to rahim.",
            )) assertEquals(p, want, m(p))
        }
    }

    @Test fun smsAnyOrder() {
        assertEquals(send("sms", "rahim", "hi"), m("say hi to rahim on sms"))
        assertEquals(send("sms", "rahim", "hi"), m("via sms tell rahim hi"))
    }

    @Test fun longerTextAndContact() {
        assertEquals(send("whatsapp", "rahim khan", "i will be late"), m("say i will be late to rahim khan on whatsapp"))
        assertEquals(send("messenger", "mom", "go to bed"), m("send go to bed to mom on messenger"))
    }

    @Test fun conflictingAppsDoNotSend() {
        val call = m("in whatsapp say hi to rahim on messenger")
        assertNotEquals(send("whatsapp", "rahim", "hi"), call)
        assertNotEquals(send("messenger", "rahim", "hi"), call)
    }

    @Test fun noAppWordDoesNotGuessSay() {
        val call = m("say hi to rahim")
        assertNotEquals("send_whatsapp", call?.tool)
    }

    @Test fun tellMeIsNotAMessage() {
        val call = m("tell me a joke on whatsapp")
        assertNotEquals("send_whatsapp", call?.tool)
    }

    @Test fun oldShapesStillWork() {
        assertEquals(ToolCall("send_whatsapp", mapOf("contact" to "rahim", "text" to "hello")), m("send whatsapp message to rahim saying hello"))
        assertEquals(ToolCall("send_whatsapp", mapOf("contact" to "rahim", "text" to "i am late")), m("send a whatsapp message to rahim saying i am late"))
    }

    private fun read(contact: String, app: String?, count: Int = 1) =
        ToolCall("read_chat", buildMap { put("contact", contact); app?.let { put("app", it) }; put("count", count.toString()) })

    @Test fun readChatPhrases() {
        assertEquals(read("rahim", "messenger"), m("open messenger read last message of rahim"))
        assertEquals(read("rahim", "messenger"), m("open messenger and read the last message from rahim"))
        assertEquals(read("rahim", "messenger"), m("read rahim's last message on messenger"))
        assertEquals(read("rahim", "whatsapp"), m("what did rahim say on whatsapp"))
        assertEquals(read("rahim", "sms"), m("what did rahim send me on sms"))
        assertEquals(read("rahim", null), m("what did rahim say"))
        assertEquals(read("rahim", "whatsapp", 3), m("read rahim's last messages on whatsapp"))
        assertEquals(read("rahim", "whatsapp", 5), m("in whatsapp read the last five messages from rahim"))
        assertEquals(read("rahim khan", "whatsapp", 3), m("read my chat with rahim khan on whatsapp"))
    }

    @Test fun readMessagesStaysGeneric() {
        assertEquals("read_messages", m("read my messages")?.tool)
        assertEquals("read_messages", m("read the last message")?.tool)
        assertNotEquals("read_chat", m("what did you say")?.tool)
    }
}

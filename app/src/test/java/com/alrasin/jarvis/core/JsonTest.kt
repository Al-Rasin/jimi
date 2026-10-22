package com.alrasin.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTest {
    @Test fun roundTrip() {
        val c = ToolCall("send_sms", mapOf("contact" to "mom", "text" to "say \"hi\"\n"))
        assertEquals(c, ToolCall.fromJson(c.toJson()))
    }

    @Test fun numbersBecomeStrings() {
        assertEquals(ToolCall("set_volume", mapOf("level" to "50")), ToolCall.fromJson("""{"tool":"set_volume","args":{"level":50}}"""))
    }

    @Test fun findsObjectInText() {
        val v = Json.firstObject("""Sure! {"tool": "chat", "args": {"text": "a {b}"}} done""") as Map<*, *>
        assertEquals("chat", v["tool"])
    }
}

package com.alrasin.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun parsesNestedValuesAndWhitespace() {
        assertEquals(
            mapOf("steps" to listOf(mapOf("level" to 50L), true, false, null), "args" to emptyMap<String, Any?>()),
            Json.parse(" \n {\"steps\": [{\"level\":50},true,false,null], \"args\":{}} \t")
        )
        assertEquals(emptyList<Any?>(), Json.parse("[]"))
    }

    @Test fun parsesSignedDecimalAndExponentNumbers() {
        assertEquals(listOf(-12L, 0L, 0.5, 1200.0, -0.025), Json.parse("[-12,0,0.5,1.2e3,-2.5E-2]"))
    }

    @Test fun decodesEscapesAndUnicode() {
        assertEquals("\"\\/\b\u000c\n\r\tA", Json.parse(""""\"\\\/\b\f\n\r\t\u0041""""))
        assertEquals("Hello, বাংলা 👋", Json.parse(Json.write("Hello, বাংলা 👋")))
    }

    @Test fun writerEscapesEveryControlCharacter() {
        val controls = (0..31).map { it.toChar() }.joinToString("")
        val encoded = Json.write(controls)
        assertTrue(encoded.none { it < ' ' })
        assertEquals(controls, Json.parse(encoded))
    }

    @Test fun writerPreservesSpeechTextAndNestedValues() {
        val value = linkedMapOf(
            "text" to "Set volume to fifty percent, PLEASE!",
            "steps" to listOf(null, true, false, 50L, mapOf("label" to "say \"hi\""))
        )
        assertEquals(value, Json.parse(Json.write(value)))
        assertEquals("50", Json.write(50.0))
        assertEquals("50.5", Json.write(50.5))
    }

    @Test fun firstObjectIgnoresEscapedQuotesAndBracesInsideStrings() {
        val value = mapOf("text" to "say \"} {\" then \\", "args" to mapOf("level" to "fifty percent"))
        assertEquals(value, Json.firstObject("```json\n${Json.write(value)}\n```"))
    }

    @Test fun firstObjectReturnsOnlyFirstCompleteObject() {
        assertEquals(mapOf("tool" to "cancel"), Json.firstObject("""{"tool":"cancel"} {"tool":"chat"}"""))
    }

    @Test fun firstObjectReturnsNullForMissingMalformedOrTruncatedObjects() {
        for (text in listOf("", "   ", "No action.", "{", "{\"text\":\"unfinished", "{\"tool\":}")) {
            assertNull(text, Json.firstObject(text))
        }
    }

    @Test fun parseRejectsEmptyTruncatedAndTrailingInput() {
        for (text in listOf("", " \n ", "{", "[", "\"unfinished", "true false", "{} trailing", "[1,]")) {
            assertTrue("Expected invalid JSON: $text", runCatching { Json.parse(text) }.isFailure)
        }
    }
}

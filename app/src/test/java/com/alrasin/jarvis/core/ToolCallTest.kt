package com.alrasin.jarvis.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolCallTest {
    @Test fun missingAndBlankArgumentsAreAbsent() {
        val call = ToolCall("create_note", mapOf("empty" to "", "spaces" to " \t\n"))
        listOf("missing", "empty", "spaces").forEach { assertNull(call.arg(it)) }
    }

    @Test fun requireReturnsArgOrAsks() {
        val call = ToolCall("open_app", mapOf("app" to "YouTube", "blank" to " "))
        assertEquals("YouTube", call.require("app", "Which app?"))
        val e = runCatching { call.require("blank", "Which app?") }.exceptionOrNull()
        assertEquals("Which app?", (e as MissingArg).ask)
    }

    @Test fun simpleToolTurnsMissingArgIntoAsk() = kotlinx.coroutines.runBlocking {
        val tool = com.alrasin.jarvis.actions.SimpleTool("t", "d") { c, _ -> ToolResult.ok(c.require("x", "X what?")) }
        val ctx = ToolContext(android.content.ContextWrapper(null), "", false)
        assertEquals(ToolResult.fail("X what?"), tool.run(ToolCall("t"), ctx))
        assertEquals(ToolResult.ok("hi"), tool.run(ToolCall("t", mapOf("x" to "hi")), ctx))
    }

    @Test fun argumentsPreserveSpeechAndCase() {
        val text = "  Set it to Fifty percent, Please!  "
        val call = ToolCall("create_note", mapOf("text" to text))
        assertEquals(text, call.arg("text"))
        assertNull(call.arg("Text"))
    }

    @Test fun parsesWhitespaceAroundCall() = assertEquals(
        ToolCall("set_volume", mapOf("level" to "fifty percent")),
        ToolCall.fromJson(" \n {\"tool\":\"set_volume\",\"args\":{\"level\":\"fifty percent\"}} \t"),
    )

    @Test fun convertsNonStringArgumentsToJsonStrings() {
        val call = ToolCall.fromJson("""{"tool":"example","args":{"level":50,"fraction":12.5,"enabled":true,"nothing":null,"steps":[{"tool":"lock_phone"}],"options":{"state":"off"}}}""")!!
        assertEquals(mapOf(
            "level" to "50", "fraction" to "12.5", "enabled" to "true", "nothing" to "null",
            "steps" to "[{\"tool\":\"lock_phone\"}]", "options" to "{\"state\":\"off\"}",
        ), call.args)
    }

    @Test fun omittedArgumentsAreEmpty() =
        assertEquals(ToolCall("lock_phone"), ToolCall.fromJson("""{"tool":"lock_phone"}"""))

    @Test fun rejectsInvalidOrNonCallJsonWithoutThrowing() {
        listOf("", " \n ", "{", "[]", "null", "true", "{}", "{\"tool\":42}",
            "{\"tool\":null}", "{\"tool\":\"lock_phone\"} trailing").forEach {
            assertNull(it, ToolCall.fromJson(it))
        }
    }

    @Test fun roundTripPreservesEscapedAndUnicodeArguments() {
        val call = ToolCall("create_note", mapOf(
            "text" to "Say \"Hi!\"\nPath C:\\notes\tবাংলা", "level" to "Fifty percent", "empty" to "",
        ))
        assertEquals(call, ToolCall.fromJson(call.toJson()))
    }

    @Test fun serializedArgumentsRemainStrings() {
        val parsed = Json.parse(ToolCall("set_volume", mapOf("level" to "50", "state" to "true")).toJson()) as Map<*, *>
        assertEquals(mapOf("level" to "50", "state" to "true"), parsed["args"])
    }
}

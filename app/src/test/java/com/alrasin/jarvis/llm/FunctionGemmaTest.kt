package com.alrasin.jarvis.llm

import com.alrasin.jarvis.actions.SimpleTool
import com.alrasin.jarvis.actions.arg
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FunctionGemmaTest {
    @Test fun parsesCallWithNoArgumentsAndLeadingWhitespace() = assertEquals(
        ToolCall("lock_phone"),
        FunctionGemma.parse(" \n<start_function_call>call:lock_phone{}<end_function_call>"),
    )

    @Test fun preservesSpeechPunctuationAndMixedCaseInEscapedValue() = assertEquals(
        ToolCall("create_note", mapOf("text" to "Fifty percent, Please! {OK}: yes?")),
        FunctionGemma.parse("<start_function_call>call:create_note{text:<escape>Fifty percent, Please! {OK}: yes?<escape>}<end_function_call>"),
    )

    @Test fun parsesMultilineEscapedMessage() = assertEquals(
        ToolCall("create_note", mapOf("text" to "First line\nSecond line, Fifty percent!")),
        FunctionGemma.parse("<start_function_call>call:create_note{text:<escape>First line\nSecond line, Fifty percent!<escape>}<end_function_call>"),
    )

    @Test fun dropsEmptyAndBlankArguments() = assertEquals(
        ToolCall("create_note", mapOf("text" to "hello")),
        FunctionGemma.parse("<start_function_call>call:create_note{title:<escape> \t <escape>,text:<escape> hello <escape>,label:}<end_function_call>"),
    )

    @Test fun parsesCompleteCallWhenEndTokenIsOmitted() = assertEquals(
        ToolCall("set_volume", mapOf("level" to "50")),
        FunctionGemma.parse("<start_function_call>call:set_volume{level:50}"),
    )

    @Test fun incompleteAndEmptyOutputAreNotCalls() {
        listOf("", " \n", "<start_function_call>", "<start_function_call>call:open_app{app:").forEach {
            assertNull(it, FunctionGemma.parse(it))
        }
    }

    private val tools = listOf(
        SimpleTool("open_app", "Open an app", listOf(arg("app", "App name"))) { _, _ -> ToolResult.ok("") },
        SimpleTool(
            "toggle", "Turn a setting on or off",
            listOf(arg("target", "What", choices = listOf("wifi", "bluetooth")), arg("state", "on/off", choices = listOf("on", "off"))),
        ) { _, _ -> ToolResult.ok("") },
    )

    @Test fun parsesCall() = assertEquals(
        ToolCall("open_app", mapOf("app" to "YouTube")),
        FunctionGemma.parse("<start_function_call>call:open_app{app:<escape>YouTube<escape>}<end_function_call>"),
    )

    @Test fun parsesManyArgsAndBareValues() = assertEquals(
        ToolCall("toggle", mapOf("target" to "wifi", "state" to "on")),
        FunctionGemma.parse("<start_function_call>call:toggle{target:<escape>wifi<escape>,state:on}<end_function_call>"),
    )

    @Test fun plainTextIsNoCall() = assertNull(FunctionGemma.parse("Sorry, I can't help with that."))

    @Test fun promptHasDeclarationsAndTurns() {
        val p = FunctionGemma.prompt(tools, "time 10:00", "open youtube")
        assertTrue(p.startsWith("<bos><start_of_turn>developer"))
        assertTrue(p.contains("<start_function_declaration>declaration:toggle{"))
        assertTrue(p.endsWith("<start_of_turn>model\n"))
    }

    @Test fun promptPrefixIsStable() {
        // same tools → same prefix → KV cache reuse
        val a = FunctionGemma.prompt(tools, "time 10:00", "open youtube")
        val b = FunctionGemma.prompt(tools, "time 10:01", "turn on wifi")
        assertTrue(a.commonPrefixWith(b).length > a.indexOf("<start_of_turn>user"))
    }

    @Test fun grammarListsEveryTool() {
        val g = FunctionGemma.grammar(tools)
        assertTrue(g.startsWith("root ::= [ \\n]* (call | text)"))
        assertTrue(g.contains("\"open_app{\""))
        assertTrue(g.contains("\"<escape>bluetooth<escape>\""))
    }
}

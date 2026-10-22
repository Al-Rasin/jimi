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

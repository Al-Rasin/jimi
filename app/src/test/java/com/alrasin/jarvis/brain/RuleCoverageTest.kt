package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Runs the rule matcher over the same cases the FunctionGemma eval uses (training/toolcall/cases.jsonl).
 * Rules may skip a case (the model handles it) but must never pick the WRONG tool.
 */
class RuleCoverageTest {
    @Test fun rulesNeverPickTheWrongTool() {
        val file = listOf(File("../training/toolcall/cases.jsonl"), File("training/toolcall/cases.jsonl")).first { it.exists() }
        var hit = 0
        val wrong = mutableListOf<String>()
        val lines = file.readLines().filter { it.isNotBlank() }
        for (line in lines) {
            @Suppress("UNCHECKED_CAST")
            val c = Json.parse(line) as Map<String, Any?>
            val got = RuleMatcher.match(c["text"] as String) ?: continue
            if (got.tool == c["tool"]) hit++ else wrong += "${c["text"]} → ${got.tool} (want ${c["tool"]})"
        }
        println("RuleMatcher: $hit/${lines.size} cases handled without the model")
        assertTrue(wrong.joinToString("\n"), wrong.isEmpty())
    }
}

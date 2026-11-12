package com.alrasin.jarvis.actions

import com.alrasin.jarvis.agent.ScreenOcr
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult

/**
 * Ask about the screen (ROADMAP #40, §I.16): "what is this", "summarize this page", "reply to this".
 * The tool only reads the screen text (tree, then OCR); [com.alrasin.jarvis.brain.ToolRunner] hands it to the
 * chat model with [ScreenAsk.prompt]. Screen text goes to the cloud only when the user turned the cloud on (D-040).
 */
object ScreenAsk {
    const val MAX_CHARS = 4000
    private val SECRET_RE = Regex("(?i)\\b(?:otp|one ?time|password|passcode|pin|cvv|verification code|security code)\\b")
    private val CODE_RE = Regex("\\b\\d{4,8}\\b")

    /** What the model is asked, by [mode]. [question] is used for "ask". */
    fun prompt(mode: String, question: String?): String = when (mode) {
        "summary" -> "Summarize the screen text in two or three short sentences."
        "reply" -> "Suggest one short, polite reply to the latest message on the screen. Give only the reply text."
        "ask" -> question?.takeIf { it.isNotBlank() } ?: "What is on the screen? Answer in one or two sentences."
        else -> "What is this? Say in one or two sentences what the screen shows and what it is about."
    }

    /** Screen text cut to size, with lines that look like codes or passwords hidden (they never need to leave). */
    fun clean(text: String): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") { line ->
        if (SECRET_RE.containsMatchIn(line)) CODE_RE.replace(line, "[hidden]") else line
    }.take(MAX_CHARS)

    /** Spoken fallback when no chat model is ready: the first lines, like "read the screen". */
    fun fallback(text: String): String = "I can't think about it right now. The screen says: " +
        text.lines().filter { it.isNotBlank() }.take(6).joinToString(". ").take(300)
}

fun askScreenTools(): List<Tool> = listOf(
    SimpleTool(
        "ask_screen",
        "Answer a question about what is on the screen: what is this, summarize this page, suggest a reply",
        listOf(
            arg("mode", "what to do with the screen", required = false, choices = listOf("what", "summary", "reply", "ask")),
            arg("question", "the question about the screen", required = false),
        ),
    ) { _, _ ->
        val acc = JarvisAccessibilityService.instance
            ?: return@SimpleTool ToolResult.fail("Screen control is off. Open Jarvis setup to turn on accessibility.")
        val text = ScreenAsk.clean(acc.screenText().ifBlank { ScreenOcr.screenText(acc) })
        if (text.isBlank()) return@SimpleTool ToolResult.ok("I don't see any text on the screen.")
        ToolResult.ok(ScreenAsk.fallback(text), text)
    },
)

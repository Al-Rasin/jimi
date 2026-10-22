package com.alrasin.jarvis.llm

import com.alrasin.jarvis.brain.Exchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormatTest {
    @Test fun splitsFinishedSentencesOnly() {
        val (done, rest) = ChatFormat.sentences("Hello Boss. The weather is nice! It might")
        assertEquals(listOf("Hello Boss.", "The weather is nice!"), done)
        assertEquals("It might", rest)
    }

    @Test fun lastSentenceWaitsForMoreText() {
        val (done, rest) = ChatFormat.sentences("Hello Boss.")
        assertTrue(done.isEmpty())
        assertEquals("Hello Boss.", rest)
    }

    @Test fun cleansMarkdownAndThink() =
        assertEquals("Hi Boss", ChatFormat.clean("<think>\nhmm\n</think> **Hi**  Boss"))

    @Test fun qwenPromptEndsInAssistantTurn() {
        val p = ChatFormat.qwen("sys", listOf(Exchange("a", "b")), "hello")
        assertTrue(p.contains("<|im_start|>user\na<|im_end|>"))
        assertTrue(p.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
        assertTrue(p.contains("hello /no_think"))
    }
}

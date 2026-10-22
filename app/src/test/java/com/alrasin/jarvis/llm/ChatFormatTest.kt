package com.alrasin.jarvis.llm

import com.alrasin.jarvis.brain.Exchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormatTest {
    @Test fun emptyAndWhitespaceBuffersArePreserved() {
        listOf("", " \t\n").forEach { assertEquals(emptyList<String>() to it, ChatFormat.sentences(it)) }
    }

    @Test fun newlineFlushesFinalSentence() =
        assertEquals(listOf("Done, Boss!") to "", ChatFormat.sentences("Done, Boss!\n"))

    @Test fun decimalNumbersAndPercentAreNotSentenceBoundaries() = assertEquals(
        listOf("Battery is 12.5 percent.") to "Next",
        ChatFormat.sentences("Battery is 12.5 percent. Next"),
    )

    @Test fun repeatedPunctuationStaysWithSentence() = assertEquals(
        listOf("Really?!", "Yes...") to "Next",
        ChatFormat.sentences("Really?! Yes... Next"),
    )

    @Test fun streamedRemainderCanBeCompletedByNextChunk() {
        val first = ChatFormat.sentences("Done. Battery is fifty")
        assertEquals(listOf("Done."), first.first)
        assertEquals(listOf("Battery is fifty percent!") to "", ChatFormat.sentences(first.second + " percent!\n"))
    }

    @Test fun cleansMultipleThinkBlocksAndKeepsSpokenPunctuation() = assertEquals(
        "Battery: Fifty percent! Ready?",
        ChatFormat.clean("<think>first\nthought</think> **Battery:** Fifty _percent_! <think>second</think> `Ready?`"),
    )

    @Test fun emptyAndFormattingOnlyRepliesCleanToEmpty() {
        listOf("", " \n\t", "**#`_", "<think>nothing to say</think>").forEach {
            assertEquals("", ChatFormat.clean(it))
        }
    }

    @Test fun promptKeepsOnlyThreeMostRecentExchangesInOrder() {
        val history = (1..5).map { Exchange("question $it", "answer $it") }
        val prompt = ChatFormat.qwen("Jimi", history, "Now?")
        val expected = "<|im_start|>system\nJimi<|im_end|>\n" +
            (3..5).joinToString("") {
                "<|im_start|>user\nquestion $it<|im_end|>\n<|im_start|>assistant\nanswer $it<|im_end|>\n"
            } + "<|im_start|>user\nNow? /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        assertEquals(expected, prompt)
    }

    @Test fun promptHandlesEmptySystemHistoryAndUser() = assertEquals(
        "<|im_start|>system\n<|im_end|>\n<|im_start|>user\n /no_think<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n",
        ChatFormat.qwen("", emptyList(), ""),
    )

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

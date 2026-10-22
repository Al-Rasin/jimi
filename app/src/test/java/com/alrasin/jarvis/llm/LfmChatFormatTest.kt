package com.alrasin.jarvis.llm

import com.alrasin.jarvis.brain.Exchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LfmChatFormatTest {
    @Test fun nativeTemplateIncludesIdentityMemoryAndOneBos() {
        val system = "You are Jimi.\nRemembered facts about the user:\nLives in Dhaka."
        val prompt = ChatFormat.lfm(system, emptyList(), "Where do I live?")
        assertEquals(
            "<|startoftext|><|im_start|>system\n$system<|im_end|>\n" +
                "<|im_start|>user\nWhere do I live?<|im_end|>\n<|im_start|>assistant\n",
            prompt,
        )
        assertFalse(prompt.contains("/no_think"))
        assertFalse(prompt.contains("<think>"))
        assertEquals(1, Regex(Regex.escape("<|startoftext|>")).findAll(prompt).count())
    }

    @Test fun followUpKeepsLastThreeExchangesInOrder() {
        val history = listOf(
            Exchange("Discard this", "Old answer"), Exchange("Capital?", "Dhaka."),
            Exchange("Country?", "Bangladesh."), Exchange("Currency?", "Taka."),
        )
        assertEquals(
            "<|startoftext|><|im_start|>system\nJimi<|im_end|>\n" +
                "<|im_start|>user\nCapital?<|im_end|>\n<|im_start|>assistant\nDhaka.<|im_end|>\n" +
                "<|im_start|>user\nCountry?<|im_end|>\n<|im_start|>assistant\nBangladesh.<|im_end|>\n" +
                "<|im_start|>user\nCurrency?<|im_end|>\n<|im_start|>assistant\nTaka.<|im_end|>\n" +
                "<|im_start|>user\nAnd its code?<|im_end|>\n<|im_start|>assistant\n",
            ChatFormat.lfm("Jimi", history, "And its code?"),
        )
    }

    @Test fun emptyContentStillHasValidTurnBoundaries() = assertEquals(
        "<|startoftext|><|im_start|>system\n<|im_end|>\n" +
            "<|im_start|>user\n<|im_end|>\n<|im_start|>assistant\n",
        ChatFormat.lfm("", emptyList(), ""),
    )
}

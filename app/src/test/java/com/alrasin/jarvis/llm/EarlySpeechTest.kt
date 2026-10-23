package com.alrasin.jarvis.llm

import com.alrasin.jarvis.voice.SpeechText
import org.junit.Assert.*
import org.junit.Test

class EarlySpeechTest {
    @Test fun unpunctuatedStreamReleasesFiveWordsBeforeGenerationEnds() {
        assertEquals(listOf("There are several ways to") to "answer",
            ChatFormat.speechChunks("There are several ways to answer"))
    }

    @Test fun toolReplyStartsWithShortChunkAndLosesNoWords() {
        val text = "Alarm set for tomorrow morning at seven thirty AM."
        val chunks = SpeechText.chunks(text, maxWords = 5, firstWords = 2)
        assertEquals("Alarm set", chunks.first())
        assertEquals(SpeechText.normalize(text), chunks.joinToString(" "))
    }

    @Test fun qwenPromptExplicitlyDisablesThinking() {
        val prompt = ChatFormat.qwen("Short spoken answer.", emptyList(), "Explain gravity")
        assertTrue(prompt.contains("/no_think"))
        assertTrue(prompt.endsWith("<think>\n\n</think>\n\n"))
        assertEquals(emptyList<String>(), ChatFormat.speechChunks("<think>Keep this internal while considering the").first)
    }
}

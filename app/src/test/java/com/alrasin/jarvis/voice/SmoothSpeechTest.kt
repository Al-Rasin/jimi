package com.alrasin.jarvis.voice

import com.alrasin.jarvis.llm.ChatFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothSpeechTest {
    @Test fun pauseFollowsPunctuation() {
        assertEquals(SpeechText.SENTENCE_PAUSE_MS, SpeechText.pauseAfterMs("Done, Boss."))
        assertEquals(SpeechText.SENTENCE_PAUSE_MS, SpeechText.pauseAfterMs("Really? "))
        assertEquals(SpeechText.CLAUSE_PAUSE_MS, SpeechText.pauseAfterMs("First of all,"))
        assertEquals(SpeechText.WORD_PAUSE_MS, SpeechText.pauseAfterMs("and then the"))
        assertTrue(SpeechText.SENTENCE_PAUSE_MS > SpeechText.CLAUSE_PAUSE_MS)
    }

    @Test fun onlyTheStartIsShortThenPiecesGrow() {
        val text = List(60) { "go" }.joinToString(" ") + "."
        val sizes = SpeechText.chunks(text, maxWords = 25, firstWords = 3).map { it.split(" ").size }
        assertEquals(listOf(3, 9, 25, 23), sizes)
    }

    @Test fun laterSentencesAreNotCutShort() {
        val text = "Alarm set for five. The weather is clear and warm today with a light wind."
        assertEquals(listOf("Alarm set", "for five.", "The weather is clear and warm today with a light wind."),
            SpeechText.chunks(text, maxWords = 25, firstWords = 2))
    }

    @Test fun streamWaitsForLongerPiecesOnceSpeaking() {
        val buf = "There are several ways to answer this question"
        assertEquals(listOf("There are several ways to"), ChatFormat.speechChunks(buf).first)
        assertEquals(emptyList<String>(), ChatFormat.speechChunks(buf.substringBefore(" this"), spoke = true).first)
        val (done, rest) = ChatFormat.speechChunks("$buf and many more ", spoke = true)
        assertEquals(listOf("There are several ways to answer this question and many"), done)
        assertEquals("more ", rest)
    }
}

package com.alrasin.jimi.voice

import com.alrasin.jimi.llm.ChatFormat
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
        val more = " and many more of them work well today for people who try them at home with friends and family "
        assertEquals(emptyList<String>(), ChatFormat.speechChunks("$buf and many more of them work well ", spoke = true).first)
        val (done, rest) = ChatFormat.speechChunks("$buf$more and in the evening too ", spoke = true)
        assertEquals(listOf("There are several ways to answer this question and many more of them work well today " +
            "for people who try them at home with friends and family"), done)
        assertEquals("and in the evening too ", rest)
    }

    @Test fun forcedCutWithoutJoiningWordKeepsAllWords() {
        val buf = List(29) { "go" }.joinToString(" ") + " "
        val (done, rest) = ChatFormat.speechChunks(buf, spoke = true)
        assertEquals(listOf(List(28) { "go" }.joinToString(" ")), done)
        assertEquals("go ", rest)
    }

    @Test fun cutAnswerEndsOnFullSentence() {
        assertEquals("Paris is big. It has a river.", ChatFormat.dropCutTail("Paris is big. It has a river. It also has many"))
        assertEquals("Done!", ChatFormat.dropCutTail("Done!"))
        assertEquals("no stop at all", ChatFormat.dropCutTail("no stop at all"))
    }
}

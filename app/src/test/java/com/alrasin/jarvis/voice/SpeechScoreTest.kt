package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechScoreTest {
    @Test fun werCountsWordEdits() {
        assertEquals(0f, SpeechScore.wer("Call mom.", "call mom"), 0.001f)
        assertEquals(0.5f, SpeechScore.wer("call mom", "call tom"), 0.001f)
        assertEquals(1f / 3, SpeechScore.wer("set an alarm", "set alarm"), 0.001f)
        assertEquals(1f / 3, SpeechScore.wer("set an alarm", "set an an alarm"), 0.001f)
        assertEquals(1f, SpeechScore.wer("open youtube", ""), 0.001f)
        assertEquals(0f, SpeechScore.wer("", " "), 0.001f)
        assertEquals(1f, SpeechScore.wer("", "noise"), 0.001f)
    }

    @Test fun wordsKeepApostrophesAndBangla() {
        assertEquals(listOf("what's", "the", "time"), SpeechScore.words("What's  the time?"))
        assertEquals(listOf("আমি", "ভাত", "খাই"), SpeechScore.words("আমি ভাত খাই।"))
    }

    @Test fun summaryWeightsRtfBySeconds() {
        val clips = listOf(
            SpeechScore.Clip("a", 1f, 0.1f, 0f),
            SpeechScore.Clip("b", 3f, 0.5f, 0.5f),
            SpeechScore.Clip("c", 4f, 0.2f, null),
        )
        assertEquals("| kroko | 3 | 0.30 | 25% (2) | 11.0 s |", SpeechScore.summaryRow("kroko", 11_000, clips))
        assertEquals("| x | 0 | 0.00 | — | 0.5 s |", SpeechScore.summaryRow("x", 500, emptyList()))
    }
}

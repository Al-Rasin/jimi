package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureEndpointTest {
    private fun CaptureEndpoint.advance(ms: Int, text: String = "", voice: Boolean = false) =
        accept(ms * 16, text, voice)

    @Test fun shortCommandEndsAfterSevenHundredMilliseconds() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(100, "turn on the flashlight", true)
        assertNull(endpoint.advance(699, "turn on the flashlight"))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(1, "turn on the flashlight"))
    }

    @Test fun unfinishedSentenceAllowsPauseAndContinuation() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(100, "send a message to", true)
        assertNull(endpoint.advance(1_200, "send a message to"))
        assertNull(endpoint.advance(100, "send a message to mom", true))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(700, "send a message to mom"))
    }

    @Test fun longSentenceUsesTwelveHundredMilliseconds() {
        val text = "please remind me to pick up the groceries tomorrow morning"
        val endpoint = CaptureEndpoint()
        endpoint.advance(100, text, true)
        assertNull(endpoint.advance(1_199, text))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(1, text))
    }

    @Test fun wakeAudioWithoutCommandStillWaitsSevenSeconds() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(300, voice = true)
        assertNull(endpoint.advance(6_699))
        assertEquals(CaptureEndpoint.FinishReason.LISTENING_TIMEOUT, endpoint.advance(1))
    }

    @Test fun followUpWaitsSixSeconds() {
        val endpoint = CaptureEndpoint(waitStartMillis = 6_000)
        assertNull(endpoint.advance(5_999))
        assertEquals(CaptureEndpoint.FinishReason.LISTENING_TIMEOUT, endpoint.advance(1))
    }

    @Test fun lateSpeechGetsBoundedDecoderGrace() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(6_900)
        assertNull(endpoint.advance(100, voice = true))
        assertNull(endpoint.advance(500, "turn on", true))
        assertNull(endpoint.advance(700, "turn on"))
    }

    @Test fun lateSpeechEndingJustBeforeDeadlineGetsDecoderGrace() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(6_800)
        endpoint.advance(100, voice = true)
        assertNull(endpoint.advance(100))
        assertNull(endpoint.advance(100, "stop"))
    }

    @Test fun noiseCannotExtendStartWaitForever() {
        val endpoint = CaptureEndpoint()
        assertNull(endpoint.advance(7_000, voice = true))
        assertEquals(CaptureEndpoint.FinishReason.LISTENING_TIMEOUT, endpoint.advance(1_200, voice = true))
    }

    @Test fun changingPartialDoesNotCountAsSilence() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(100, "turn", true)
        endpoint.advance(600, "turn")
        assertNull(endpoint.advance(100, "turn on the flashlight"))
        assertNull(endpoint.advance(699, "turn on the flashlight"))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(1, "turn on the flashlight"))
    }

    @Test fun audibleSpeechPreventsEndpointWhileTextStaysStable() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(100, "call mom", true)
        assertNull(endpoint.advance(1_500, "call mom", true))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(700, "call mom"))
    }

    @Test fun overlapTextStartsCommandWithoutCountingPreRollAsElapsedTime() {
        val endpoint = CaptureEndpoint()
        endpoint.advance(0, "turn on the flashlight")
        assertNull(endpoint.advance(699, "turn on the flashlight"))
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(1, "turn on the flashlight"))
    }

    @Test fun continuousSpeechStopsAtTwentySecondsAndStaysFinished() {
        val endpoint = CaptureEndpoint()
        assertNull(endpoint.advance(19_999, "keep talking", true))
        assertEquals(CaptureEndpoint.FinishReason.MAX_LENGTH, endpoint.advance(1, "keep talking", true))
        assertEquals(CaptureEndpoint.FinishReason.MAX_LENGTH, endpoint.advance(1, ""))
    }

    @Test fun vadRejectsSilenceLowNoiseAndDcOffset() {
        val vad = CaptureVoiceActivity()
        assertFalse(vad.hasSpeech(ShortArray(1_280)))
        assertFalse(vad.hasSpeech(ShortArray(1_280) { if (it % 2 == 0) 30 else -30 }))
        assertFalse(vad.hasSpeech(ShortArray(1_280) { 1_000 }))
    }

    @Test fun vadFindsSpeechInPartialFrameAndIgnoresUnusedBuffer() {
        val vad = CaptureVoiceActivity()
        val pcm = ShortArray(1_280) { if (it % 2 == 0) 1_000 else -1_000 }
        assertTrue(vad.hasSpeech(pcm, 100))
        assertFalse(vad.hasSpeech(pcm, 0))
    }

    @Test fun vadAdaptsToSustainedFanWithoutWaitingForMaximumLength() {
        val vad = CaptureVoiceActivity()
        val fan = ShortArray(1_280) { if (it % 2 == 0) 500 else -500 }
        repeat(16) { vad.hasSpeech(fan) }
        assertFalse(vad.hasSpeech(fan))
        val speech = ShortArray(1_280) { if (it % 2 == 0) 4_000 else -4_000 }
        assertTrue(vad.hasSpeech(speech))
        val endpoint = CaptureEndpoint()
        endpoint.advance(80, "turn on the flashlight", true)
        repeat(8) { assertNull(endpoint.advance(80, "turn on the flashlight", vad.hasSpeech(fan))) }
        assertEquals(CaptureEndpoint.FinishReason.SILENCE, endpoint.advance(80, "turn on the flashlight", vad.hasSpeech(fan)))
    }

    @Test fun vadDoesNotAdaptOrdinaryBurstsOfSpeechIntoNoise() {
        val vad = CaptureVoiceActivity()
        val speech = ShortArray(1_280) { if (it % 2 == 0) 1_000 else -1_000 }
        repeat(20) {
            assertTrue(vad.hasSpeech(speech))
            assertFalse(vad.hasSpeech(ShortArray(1_280)))
        }
    }
}

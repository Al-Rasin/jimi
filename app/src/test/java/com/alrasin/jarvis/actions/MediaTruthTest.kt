package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MediaTruthTest {
    @Test fun openingWithoutAudioDoesNotClaimPlayback() {
        assertEquals("I opened it, Boss.", MediaTruth.opened(false, false).say)
        assertEquals("I opened it, Boss.", MediaTruth.opened(false, null).say)
    }

    @Test fun existingOrUnknownPlaybackDoesNotVerifyTheNewRequest() {
        assertEquals("I opened it, Boss.", MediaTruth.opened(true, true).say)
        assertEquals("I opened it, Boss.", MediaTruth.opened(null, true).say)
    }

    @Test fun newlyActiveAudioReportsOnlyObservedAudio() {
        assertEquals("I opened it. Audio is playing, Boss.", MediaTruth.opened(false, true).say)
    }

    @Test fun playAndPauseRequireMatchingReadback() {
        assertEquals("Audio is playing, Boss.", MediaTruth.control("play", true).say)
        assertEquals("No music is playing, Boss.", MediaTruth.control("pause", false).say)
        for (state in listOf(false, null)) {
            assertFalse(MediaTruth.control("play", state).say.contains("is playing"))
        }
        for (state in listOf(true, null)) {
            assertEquals("Pause requested, Boss. I couldn't confirm it.", MediaTruth.control("pause", state).say)
        }
    }

    @Test fun trackChangesRemainRequestsEvenIfAudioIsActive() {
        assertEquals("Next track requested, Boss.", MediaTruth.control("next", true).say)
        assertEquals("Previous track requested, Boss.", MediaTruth.control("previous", true).say)
    }
}

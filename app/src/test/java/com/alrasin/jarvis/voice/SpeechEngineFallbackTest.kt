package com.alrasin.jarvis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechEngineFallbackTest {
    private val selector = SpeechEngineFallback(30)

    @Test fun defaultsToOfflineForMissingOrUnknownPreference() {
        assertEquals(SpeechEngine.JIMI_OFFLINE, SpeechEngine.fromStored(null))
        assertEquals(SpeechEngine.JIMI_OFFLINE, SpeechEngine.fromStored("unknown"))
        assertEquals(SpeechEngine.PHONE_ON_DEVICE, SpeechEngine.fromStored("PHONE_ON_DEVICE"))
    }

    @Test fun offlineDoesNotStartPhone() = runBlocking {
        assertEquals("open settings", selector.transcript(SpeechEngine.JIMI_OFFLINE, "open settings") {
            error("must not start")
        })
    }

    @Test fun usesPhoneResult() = runBlocking {
        assertEquals("open settings", selector.transcript(SpeechEngine.PHONE_ON_DEVICE, "open seating") { "open settings" })
    }

    @Test fun unavailableAndBlankKeepSherpa() = runBlocking {
        for (result in listOf(null, "", "  ")) {
            assertEquals("fallback", selector.transcript(SpeechEngine.PHONE_ON_DEVICE, "fallback") { result })
        }
    }

    @Test fun errorKeepsSherpa() = runBlocking {
        assertEquals("fallback", selector.transcript(SpeechEngine.PHONE_ON_DEVICE, "fallback") {
            throw IllegalStateException("unavailable")
        })
    }

    @Test fun timeoutKeepsSherpa() = runBlocking {
        assertEquals("fallback", selector.transcript(SpeechEngine.PHONE_ON_DEVICE, "fallback") { delay(1_000); "late" })
    }

    @Test(expected = CancellationException::class)
    fun interruptionDoesNotSubmitFallback() { runBlocking {
        selector.transcript(SpeechEngine.PHONE_ON_DEVICE, "fallback") { throw CancellationException() }
    } }
}

package com.alrasin.jarvis.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneRetryTest {
    @Test fun repeatedFailureBacksOffAndCapsWithoutOverflow() {
        val retry = MicrophoneRetry()
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L), List(5) { retry.nextDelayMillis() })
        repeat(100) { assertEquals(15_000L, retry.nextDelayMillis()) }
    }

    @Test fun successfulCaptureResetsRecoveryDelay() {
        val retry = MicrophoneRetry()
        repeat(5) { retry.nextDelayMillis() }
        retry.recovered()
        assertEquals(1_000L, retry.nextDelayMillis())
    }
}

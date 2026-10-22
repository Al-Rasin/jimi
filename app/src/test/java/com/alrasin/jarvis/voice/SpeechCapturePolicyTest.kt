package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechCapturePolicyTest {
    @Test fun phoneFailureFallsBackForExactlyTheNextCapture() {
        val policy = SpeechCapturePolicy()
        assertEquals(SpeechEngine.PHONE_ON_DEVICE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
        policy.phoneFailed()
        assertEquals(SpeechEngine.JIMI_OFFLINE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
        assertEquals(SpeechEngine.PHONE_ON_DEVICE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
    }

    @Test fun switchingPreferenceResetsPendingFallback() {
        val policy = SpeechCapturePolicy()
        policy.select(SpeechEngine.PHONE_ON_DEVICE)
        policy.phoneFailed()
        assertEquals(SpeechEngine.JIMI_OFFLINE, policy.select(SpeechEngine.JIMI_OFFLINE))
        assertEquals(SpeechEngine.PHONE_ON_DEVICE, policy.select(SpeechEngine.PHONE_ON_DEVICE))
    }
}

package com.alrasin.jarvis.voice

/** A failed phone utterance is discarded; the next capture uses sherpa. */
class SpeechCapturePolicy {
    private var previous = SpeechEngine.JIMI_OFFLINE
    private var fallbackNext = false

    fun select(preference: SpeechEngine): SpeechEngine {
        if (preference != previous) fallbackNext = false
        previous = preference
        if (preference == SpeechEngine.JIMI_OFFLINE) return preference
        if (fallbackNext) {
            fallbackNext = false
            return SpeechEngine.JIMI_OFFLINE
        }
        return preference
    }

    fun phoneFailed() { fallbackNext = true }
}

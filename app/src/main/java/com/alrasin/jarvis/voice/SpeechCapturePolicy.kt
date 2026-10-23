package com.alrasin.jarvis.voice

/** Retry Google for every online capture; retain the one-capture offline fallback. */
class SpeechCapturePolicy {
    private var previous = SpeechEngine.JIMI_OFFLINE
    private var fallbackNext = false

    fun select(preference: SpeechEngine, online: Boolean = false): SpeechEngine {
        if (preference != previous) fallbackNext = false
        previous = preference
        if (preference == SpeechEngine.JIMI_OFFLINE) return preference
        if (online) {
            fallbackNext = false
            return preference
        }
        if (fallbackNext) {
            fallbackNext = false
            return SpeechEngine.JIMI_OFFLINE
        }
        return preference
    }

    fun phoneFailed() { fallbackNext = true }
}

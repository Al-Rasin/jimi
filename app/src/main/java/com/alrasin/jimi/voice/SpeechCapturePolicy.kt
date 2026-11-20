package com.alrasin.jimi.voice

/**
 * Picks the engine for each capture. After a phone (Google) failure, exactly the next capture uses the
 * offline engine, online or not; the one after goes back to the phone engine.
 */
class SpeechCapturePolicy {
    private var previous = SpeechEngine.JIMI_OFFLINE
    private var fallbackNext = false

    @Suppress("UNUSED_PARAMETER") // kept for callers; a failure falls back even when online
    fun select(preference: SpeechEngine, online: Boolean = false): SpeechEngine {
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

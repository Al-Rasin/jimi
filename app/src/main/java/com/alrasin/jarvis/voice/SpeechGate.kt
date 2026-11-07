package com.alrasin.jarvis.voice

/**
 * The chat LLM (4 threads) and the phone voice fight for CPU and RAM on the 4 GB phone: while both ran,
 * 1 s of speech took ~3–4 s to make and the answer paused between phrases (seen 2026-11-07).
 * The voice marks itself busy here; the LLM waits between tokens until the phrase is made.
 * The LLM is faster than speech, so it catches up while the phrase plays.
 */
object SpeechGate {
    @Volatile private var busySince = 0L

    fun <T> synth(block: () -> T): T {
        busySince = System.currentTimeMillis()
        try {
            return block()
        } finally {
            busySince = 0L
        }
    }

    /** Called by the LLM between tokens. Never waits longer than [MAX_WAIT_MS] for one phrase. */
    fun yieldToVoice(): Long {
        var waited = 0L
        while (true) {
            val since = busySince
            if (since == 0L || System.currentTimeMillis() - since > MAX_WAIT_MS) return waited
            Thread.sleep(STEP_MS)
            waited += STEP_MS
        }
    }

    private const val STEP_MS = 15L
    private const val MAX_WAIT_MS = 4_000L
}

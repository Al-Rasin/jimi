package com.alrasin.jarvis.voice

import kotlin.math.abs

/**
 * Makes one spoken piece fit smoothly after the last one. Voices add their own silence at both ends
 * (often 100-300 ms each), so pieces played back to back sounded broken. Cut that silence, keep a little
 * air around the words, fade the edges (no click), then add the pause we want ([SpeechText.pauseAfterMs]).
 */
object PcmShape {
    /** Below this a sample counts as silence (float PCM, full scale 1.0). */
    const val QUIET = 0.006f
    private const val KEEP_BEFORE_MS = 40
    private const val KEEP_AFTER_MS = 60
    private const val FADE_MS = 6

    /** Returns an empty array when [pcm] has no sound at all. */
    fun shape(pcm: FloatArray, rate: Int, pauseMs: Int = 0): FloatArray {
        val first = pcm.indexOfFirst { abs(it) > QUIET }
        if (first < 0 || rate <= 0) return FloatArray(0)
        val last = pcm.indexOfLast { abs(it) > QUIET }
        val from = (first - rate * KEEP_BEFORE_MS / 1000).coerceAtLeast(0)
        val to = (last + 1 + rate * KEEP_AFTER_MS / 1000).coerceAtMost(pcm.size)
        val length = to - from
        val out = FloatArray(length + rate * pauseMs.coerceAtLeast(0) / 1000)
        pcm.copyInto(out, 0, from, to)
        val fade = minOf(rate * FADE_MS / 1000, length / 2)
        for (i in 0 until fade) {
            val gain = i.toFloat() / fade
            out[i] *= gain
            out[length - 1 - i] *= gain
        }
        return out
    }
}

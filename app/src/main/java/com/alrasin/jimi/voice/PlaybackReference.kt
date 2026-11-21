package com.alrasin.jimi.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Builds the echo canceler's far-end signal (D-103): for each 80 ms mic chunk, the 16 kHz audio Jimi played
 * in the 80 ms before it was read. The playback head jitters by ~60 ms between reads, so the reference
 * advances by exactly one chunk each call and only re-locks to the head on a big drift or a new stream;
 * the canceler finds the remaining (steady) delay itself.
 */
class PlaybackReference(private val source: Source = TtsSource) {
    data class Clock(val id: Int, val rate: Int, val head: Long)

    interface Source {
        fun clock(): Clock?
        fun copy(id: Int, from: Long, out: FloatArray): Boolean
    }

    private object TtsSource : Source {
        override fun clock() = TextToSpeech.playbackClock()
        override fun copy(id: Int, from: Long, out: FloatArray) = TextToSpeech.copyPlayed(id, from, out)
    }

    private var id = 0
    private var rate = 0
    private var pos16 = 0L   // 16 kHz sample index (in stream time) of the next reference sample
    private val out = ShortArray(CHUNK)
    private var src = FloatArray(0)
    private var kernel = Kernel(1.0)

    fun reset() {
        id = 0
    }

    /** Next 1280-sample reference chunk (all zero when nothing is playing). Reuses one array. */
    fun next(): ShortArray {
        val clock = source.clock()
        if (clock == null || clock.rate <= 0) {
            id = 0
            out.fill(0)
            return out
        }
        val target = clock.head * SAMPLE_RATE / clock.rate - CHUNK
        if (clock.id != id || clock.rate != rate || abs(pos16 - target) > RELOCK) {
            if (clock.rate != rate) kernel = Kernel(minOf(1.0, SAMPLE_RATE.toDouble() / clock.rate) * CUTOFF)
            id = clock.id
            rate = clock.rate
            pos16 = target
        }
        resample(pos16)
        pos16 += CHUNK
        return out
    }

    private fun resample(start16: Long) {
        val step = rate.toDouble() / SAMPLE_RATE
        val w = kernel.halfWidth
        val first = floor(start16 * step).toLong() - w
        val last = ceil((start16 + CHUNK) * step).toLong() + w
        val n = (last - first + 1).toInt()
        if (src.size != n) src = FloatArray(n)
        if (!source.copy(id, first, src)) {
            id = 0
            out.fill(0)
            return
        }
        for (i in 0 until CHUNK) {
            val x = (start16 + i) * step - first
            val c = floor(x).toInt()
            var acc = 0.0
            for (k in c - w + 1..c + w) {
                if (k in 0 until n) acc += src[k] * kernel.at(x - k)
            }
            out[i] = (acc * 32767).roundToInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** Hann-windowed sinc low-pass; [scale] is the cutoff as a fraction of the source Nyquist. */
    private class Kernel(val scale: Double) {
        val halfWidth = ceil(ZEROS / scale).toInt()
        fun at(t: Double): Double {
            if (abs(t) >= halfWidth) return 0.0
            val window = 0.5 + 0.5 * cos(PI * t / halfWidth)
            val a = PI * scale * t
            return scale * window * (if (abs(a) < 1e-9) 1.0 else sin(a) / a)
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 1280
        private const val RELOCK = 2400L   // 150 ms
        private const val CUTOFF = 0.92
        private const val ZEROS = 8
    }
}

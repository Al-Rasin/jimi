package com.alrasin.jarvis.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChimeSynthTest {
    private val cues = listOf(
        ChimeSynth.wake() to 300,
        ChimeSynth.endListening() to 180,
        ChimeSynth.listen() to 170,
        ChimeSynth.done() to 260,
        ChimeSynth.error() to 220,
    )

    @Test fun lengthsMatchTheirDurations() {
        for ((pcm, milliseconds) in cues) {
            assertEquals(ChimeSynth.SAMPLE_RATE * milliseconds / 1000, pcm.size)
        }
    }

    @Test fun allCuesAreFiniteAudibleAndHaveHeadroom() {
        for ((pcm, _) in cues) {
            assertTrue(pcm.all { it.isFinite() && abs(it) < 0.4f })
            assertTrue(pcm.any { abs(it) > 0.05f })
        }
    }

    @Test fun allCuesFadeInAndOutToExactSilence() {
        for ((pcm, duration) in cues) {
            assertEquals(0f, pcm.first(), 0f)
            assertEquals(0f, pcm.last(), 0f)
            assertTrue(power(pcm, 0, 5) < power(pcm, 20, 25) * 0.05)
            assertTrue(power(pcm, duration - 5, duration) < power(pcm, duration - 30, duration - 25) * 0.01)
        }
    }

    @Test fun wakeRisesAndEndListeningFalls() {
        val wake = ChimeSynth.wake()
        assertTrue(tonePower(wake, 640.0, 20, 65) > 20 * tonePower(wake, 880.0, 20, 65))
        assertTrue(tonePower(wake, 880.0, 240, 280) > 20 * tonePower(wake, 640.0, 240, 280))
        val end = ChimeSynth.endListening()
        assertTrue(tonePower(end, 820.0, 15, 40) > 20 * tonePower(end, 620.0, 15, 40))
        assertTrue(tonePower(end, 620.0, 135, 165) > 20 * tonePower(end, 820.0, 135, 165))
    }

    @Test fun endListeningIsQuieterThanWake() {
        assertTrue(power(ChimeSynth.endListening(), 0, 180) < power(ChimeSynth.wake(), 0, 300))
    }

    private fun power(pcm: FloatArray, fromMs: Int, toMs: Int): Double {
        val start = fromMs * ChimeSynth.SAMPLE_RATE / 1000
        val end = toMs * ChimeSynth.SAMPLE_RATE / 1000
        var sum = 0.0
        for (i in start until end) sum += pcm[i].toDouble() * pcm[i]
        return sum / (end - start)
    }

    private fun tonePower(pcm: FloatArray, hz: Double, fromMs: Int, toMs: Int): Double {
        val start = fromMs * ChimeSynth.SAMPLE_RATE / 1000
        val end = toMs * ChimeSynth.SAMPLE_RATE / 1000
        var real = 0.0
        var imaginary = 0.0
        for (i in start until end) {
            val phase = 2 * PI * hz * i / ChimeSynth.SAMPLE_RATE
            real += pcm[i] * cos(phase)
            imaginary += pcm[i] * sin(phase)
        }
        return real * real + imaginary * imaginary
    }
}

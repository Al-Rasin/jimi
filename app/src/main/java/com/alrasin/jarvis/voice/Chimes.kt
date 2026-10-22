package com.alrasin.jarvis.voice

import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * Soft bell sounds instead of the old ToneGenerator beeps (user asked for a nicer, Siri-like wake sound).
 * Made in code (no audio files): each note is a sine with a few quiet overtones, a fast attack and a long
 * smooth fade. Played on the assistant stream on a single background thread, so callers never block.
 */
class Chimes : AutoCloseable {
    private val player = Executors.newSingleThreadExecutor()

    /** "Hey Jimi" heard: two notes going up, light and bright. */
    private val wake = render(listOf(Note(E5, 0, 0.28f), Note(B5, 90, 0.34f)), 520)
    /** Tap-to-talk / start of recording: one soft high note. */
    private val listen = render(listOf(Note(A5, 0, 0.3f)), 320)
    /** Done (voice ID saved): three notes going up. */
    private val done = render(listOf(Note(C5, 0, 0.25f), Note(E5, 80, 0.25f), Note(G5, 160, 0.3f)), 560)
    /** Nothing heard: two low notes going down. */
    private val error = render(listOf(Note(E5, 0, 0.22f), Note(B4, 110, 0.26f)), 480)

    fun wake() = play(wake)
    fun listen() = play(listen)
    fun done() = play(done)
    fun error() = play(error)

    private fun play(pcm: FloatArray) {
        runCatching { player.execute { runCatching { TextToSpeech.playPcm(pcm, RATE) } } }
    }

    override fun close() = player.shutdown()

    private data class Note(val hz: Double, val startMs: Int, val gain: Float)

    companion object {
        private const val RATE = 24_000
        private const val B4 = 493.88
        private const val C5 = 523.25
        private const val E5 = 659.25
        private const val G5 = 783.99
        private const val A5 = 880.0
        private const val B5 = 987.77
        /** Overtones of a small bell: (frequency × , loudness). */
        private val PARTIALS = listOf(1.0 to 1.0, 2.0 to 0.28, 3.01 to 0.08, 4.17 to 0.04)

        private fun render(notes: List<Note>, lengthMs: Int): FloatArray {
            val out = FloatArray(RATE * lengthMs / 1000)
            for (n in notes) {
                val start = RATE * n.startMs / 1000
                for (i in start until out.size) {
                    val t = (i - start).toDouble() / RATE
                    val attack = min(1.0, t / 0.006)          // 6 ms fade in = no click
                    val decay = exp(-t * 7.5)                  // bell-like fade out
                    var v = 0.0
                    for ((k, g) in PARTIALS) v += g * exp(-t * 4.0 * (k - 1)) * sin(2 * PI * n.hz * k * t)
                    out[i] += (v * attack * decay * n.gain).toFloat()
                }
            }
            // last 30 ms fade to zero so the end never clicks
            val tail = RATE * 30 / 1000
            for (j in 0 until tail) out[out.size - 1 - j] *= j.toFloat() / tail
            return out
        }
    }
}

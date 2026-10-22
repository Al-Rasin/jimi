package com.alrasin.jarvis.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

object ChimeSynth {
    const val SAMPLE_RATE = 48_000
    const val WAKE_MS = 300

    fun wake(): FloatArray = render(WAKE_MS,
        Note(640.0, 0, 205, 0.18),
        Note(880.0, 75, 225, 0.20),
    )

    fun endListening(): FloatArray = render(180,
        Note(820.0, 0, 120, 0.11),
        Note(620.0, 45, 135, 0.12),
    )

    fun listen(): FloatArray = render(170, Note(780.0, 0, 170, 0.13))

    fun done(): FloatArray = render(260,
        Note(640.0, 0, 140, 0.12),
        Note(810.0, 60, 150, 0.12),
        Note(960.0, 120, 140, 0.14),
    )

    fun error(): FloatArray = render(220,
        Note(520.0, 0, 150, 0.12),
        Note(390.0, 65, 155, 0.13),
    )

    private data class Note(val hz: Double, val startMs: Int, val lengthMs: Int, val gain: Double)

    private fun render(lengthMs: Int, vararg notes: Note): FloatArray {
        val pcm = FloatArray(SAMPLE_RATE * lengthMs / 1000)
        val attackSamples = SAMPLE_RATE * 18 / 1000
        val releaseSamples = SAMPLE_RATE * 70 / 1000
        for (note in notes) {
            val start = SAMPLE_RATE * note.startMs / 1000
            val count = SAMPLE_RATE * note.lengthMs / 1000
            for (sample in 0 until count) {
                val attack = 0.5 - 0.5 * cos(PI * min(1.0, sample.toDouble() / attackSamples))
                val release = 0.5 - 0.5 * cos(PI * min(1.0, (count - 1 - sample).toDouble() / releaseSamples))
                val seconds = sample.toDouble() / SAMPLE_RATE
                val envelope = attack * release * exp(-6.0 * seconds)
                pcm[start + sample] += (note.gain * envelope * sin(2 * PI * note.hz * seconds)).toFloat()
            }
        }
        return pcm
    }
}

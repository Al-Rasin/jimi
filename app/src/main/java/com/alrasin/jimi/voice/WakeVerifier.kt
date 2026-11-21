package com.alrasin.jimi.voice

import android.content.Context
import com.alrasin.jimi.core.JimiLog
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp

/**
 * Second check on a high wake score (D-100): logistic regression on the 16x96 feature window, trained on
 * this phone's own real wakes and TV/near-miss clips (training/wakeword/train_verifier.py).
 * On real clips it cut instant false wakes from 8% to 1% while keeping most real calls.
 * File `<wakeword override dir>/verifier.bin` (float32 LE): mean[N], scale[N], coef[N], intercept. N = 16*96.
 * Holds personal voice data, so it is pushed with adb and never shipped in the APK. No file = no gate.
 */
class WakeVerifier(private val mean: FloatArray, private val scale: FloatArray, private val coef: FloatArray, private val bias: Float) {

    /** Probability 0..1 that [window] is the owner's real "Hey Jimi". */
    fun score(window: FloatArray): Float {
        require(window.size == mean.size) { "window ${window.size} != ${mean.size}" }
        var z = bias.toDouble()
        for (i in window.indices) z += (window[i] - mean[i]) / scale[i] * coef[i]
        return (1.0 / (1.0 + exp(-z))).toFloat()
    }

    companion object {
        const val FILE = "verifier.bin"
        const val PASS = 0.3f   // real clips: keeps ~70% of real instant wakes, passes ~1% of TV/near misses
        const val SIZE = WakeWordDetector.FEATURE_WINDOW * WakeWordDetector.EMB_DIM

        fun parse(bytes: ByteArray): WakeVerifier? {
            if (bytes.size != (3 * SIZE + 1) * 4) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            val mean = FloatArray(SIZE).also { b.get(it) }
            val scale = FloatArray(SIZE).also { b.get(it) }
            val coef = FloatArray(SIZE).also { b.get(it) }
            if (scale.any { it == 0f || it.isNaN() }) return null
            return WakeVerifier(mean, scale, coef, b.get())
        }

        fun load(context: Context): WakeVerifier? {
            val f = File(WakeModels.overrideDir(context), FILE)
            if (!f.isFile) return null
            return parse(f.readBytes()).also { JimiLog.i(if (it == null) "wake verifier: bad file" else "wake verifier loaded") }
        }
    }
}

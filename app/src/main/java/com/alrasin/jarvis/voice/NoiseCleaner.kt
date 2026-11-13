package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import java.io.File

/**
 * Noise cleaning (ROADMAP B §K 3.5, D-076): a small GTCRN model removes fan, traffic and crowd noise from the
 * command audio before speech-to-text. Only command audio is cleaned; the wake word and stop listener stay raw.
 * Optional: off by default, and any load or run error gives the raw audio back.
 * Used only from the listen thread.
 */
class NoiseCleaner(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var denoiser: OnlineSpeechDenoiser? = null
    private var failed = false

    fun ready(): Boolean = !failed && PortableModels.denoiseReady(context)

    /** Start of a new command: forget audio from the last one. */
    fun reset() {
        runCatching { denoiser?.reset() }
    }

    /** Cleaned copy of the first [count] samples; the raw copy if cleaning is off, missing or broken. */
    fun clean(chunk: ShortArray, enabled: Boolean, count: Int = chunk.size): ShortArray {
        if (!NoiseClean.use(enabled, ready())) return chunk.copyOf(count)
        return try {
            val model = denoiser ?: load().also { denoiser = it }
            NoiseClean.toShorts(model.run(NoiseClean.toFloats(chunk, count), SpeechToText.SAMPLE_RATE).samples)
        } catch (failure: Throwable) {
            failed = true
            JarvisLog.e("noise cleaning off: model failed", failure)
            close()
            chunk.copyOf(count)
        }
    }

    private fun load(): OnlineSpeechDenoiser {
        val started = System.nanoTime()
        val file = File(PortableModels.dir(context, NoiseClean.GROUP), NoiseClean.MODEL)
        return OnlineSpeechDenoiser(null, OnlineSpeechDenoiserConfig(
            model = OfflineSpeechDenoiserModelConfig(
                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = file.path),
                numThreads = 1, provider = "cpu",
            ),
        )).also {
            check(it.sampleRate == SpeechToText.SAMPLE_RATE) { "denoiser rate ${it.sampleRate}" }
            JarvisLog.i("noise cleaner loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
    }

    override fun close() {
        runCatching { denoiser?.release() }
        denoiser = null
    }
}

/** Pure parts of noise cleaning, unit tested. */
object NoiseClean {
    const val GROUP = "denoise"
    const val MODEL = "gtcrn_simple.onnx"

    fun use(enabled: Boolean, ready: Boolean) = enabled && ready

    fun toFloats(chunk: ShortArray, count: Int = chunk.size) = FloatArray(count) { chunk[it] / 32768f }

    /** Model output can go past ±1.0; clip instead of wrapping around. */
    fun toShorts(samples: FloatArray) = ShortArray(samples.size) {
        (samples[it] * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import java.io.File
import kotlin.math.roundToInt

class Denoiser private constructor(private val engine: OfflineSpeechDenoiser) : AutoCloseable {
    private var closed = false

    // Run on a worker thread with a complete 16 kHz mono utterance, not individual mic chunks.
    @Synchronized
    fun denoise(audio: ShortArray): ShortArray? {
        if (closed) return null
        if (audio.isEmpty()) return ShortArray(0)
        return runCatching {
            val result = engine.run(FloatArray(audio.size) { audio[it] / 32768f }, SpeechToText.SAMPLE_RATE)
            check(result.sampleRate == SpeechToText.SAMPLE_RATE) { "Unexpected denoiser sample rate" }
            ShortArray(result.samples.size) {
                (result.samples[it] * 32768f).roundToInt().coerceIn(-32768, 32767).toShort()
            }
        }.onFailure { JarvisLog.e("Denoising failed", it) }.getOrNull()
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            engine.release()
        }
    }

    companion object {
        fun load(context: Context): Denoiser? {
            val model = File(context.filesDir, "denoise/gtcrn.onnx")
            if (!model.isFile) return null
            return runCatching {
                Denoiser(
                    OfflineSpeechDenoiser(
                        null,
                        OfflineSpeechDenoiserConfig(
                            model = OfflineSpeechDenoiserModelConfig(
                                gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = model.absolutePath),
                                numThreads = 1,
                                provider = "cpu",
                            ),
                        ),
                    ),
                )
            }.onFailure { JarvisLog.e("Denoiser model failed to load", it) }.getOrNull()
        }
    }
}

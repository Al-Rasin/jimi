package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

class SecondPassSpeechToText(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var recognizer: OfflineRecognizer? = null
    private val decoder = BoundedSpeechDecoder(
        decode = ::decode,
        release = { recognizer?.release(); recognizer = null },
        prepare = {
            val started = System.nanoTime()
            recognizer = load()
            JarvisLog.i("STT second pass loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        },
        onFallback = { JarvisLog.i("STT second pass fallback: $it") },
    )

    suspend fun refine(audio: ShortArray, streamingText: String, enabled: Boolean, stripWake: Boolean): String {
        if (!enabled) return streamingText
        if (!PortableModels.secondPassReady(context)) {
            JarvisLog.i("STT second pass fallback: model missing")
            return streamingText
        }
        val decoded = decoder.refine(audio, streamingText, true)
        if (decoded == streamingText) return streamingText
        val normalized = SpeechToText.normalize(decoded)
        return (if (stripWake) WakePhrase.strip(normalized) else normalized).ifBlank { streamingText }
    }

    private fun decode(audio: ShortArray): String {
        val started = System.nanoTime()
        val model = checkNotNull(recognizer)
        val stream = model.createStream()
        try {
            stream.acceptWaveform(FloatArray(audio.size) { audio[it] / 32768f }, SpeechToText.SAMPLE_RATE)
            model.decode(stream)
            return model.getResult(stream).text
        } finally {
            stream.release()
            JarvisLog.i("STT second pass decode ${(System.nanoTime() - started) / 1_000_000} ms, audio ${audio.size * 1_000L / SpeechToText.SAMPLE_RATE} ms")
        }
    }

    private fun load(): OfflineRecognizer {
        val dir = PortableModels.dir(context, "stt_second_pass")
        fun path(name: String) = File(dir, name).path
        return OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechToText.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                moonshine = OfflineMoonshineModelConfig(
                    preprocessor = path("preprocess.onnx"),
                    encoder = path("encode.int8.onnx"),
                    uncachedDecoder = path("uncached_decode.int8.onnx"),
                    cachedDecoder = path("cached_decode.int8.onnx"),
                ),
                tokens = path("tokens.txt"), numThreads = 2, provider = "cpu",
            ),
        ))
    }

    override fun close() = decoder.close()
}

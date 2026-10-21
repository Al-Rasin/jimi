package com.alrasin.jarvis.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/**
 * Streaming speech-to-text (PLAN 3.2, D-025): sherpa-onnx zipformer en 20M from APK assets `stt/`.
 * One [Session] per command: feed 16 kHz chunks, read [Session.text], stop when [Session.done].
 */
class SpeechToText(assets: AssetManager) : AutoCloseable {
    private val recognizer = OnlineRecognizer(
        assets,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$DIR/encoder.int8.onnx",
                    decoder = "$DIR/decoder.int8.onnx",
                    joiner = "$DIR/joiner.int8.onnx",
                ),
                tokens = "$DIR/tokens.txt",
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                // nothing said yet: give up after 3 s of silence
                rule1 = EndpointRule(false, 3.0f, 0f),
                // something said: stop after 0.8 s of silence
                rule2 = EndpointRule(true, 0.8f, 0f),
                // hard limit
                rule3 = EndpointRule(false, 0f, MAX_SECONDS),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    fun session() = Session(recognizer.createStream()).also {
        // the model drops the first words if speech starts at sample 0
        it.accept(ShortArray(LEAD_SILENCE))
    }

    inner class Session(private val stream: OnlineStream) : AutoCloseable {
        var text = ""
            private set
        var done = false
            private set

        fun accept(chunk: ShortArray, count: Int = chunk.size) {
            if (done) return
            stream.acceptWaveform(FloatArray(count) { chunk[it] / 32768f }, SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            text = recognizer.getResult(stream).text.trim().lowercase()
            if (recognizer.isEndpoint(stream)) done = true
        }

        override fun close() = stream.release()
    }

    override fun close() = recognizer.release()

    companion object {
        private const val DIR = "stt"
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 10f
        private const val LEAD_SILENCE = SAMPLE_RATE / 2
    }
}

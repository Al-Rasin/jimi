package com.alrasin.jarvis.voice

import android.content.Context
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File

/**
 * Streaming speech-to-text (PLAN 3.2, D-026): sherpa-onnx transducer (Kroko zipformer en) from APK assets `stt/`.
 * A transducer model folder in `<external files>/models/stt/` (encoder*, decoder*, joiner*.onnx + tokens.txt) is used
 * instead when present — or [dir] for tests — so other models can be tried without a rebuild.
 * One [Session] per command: feed 16 kHz chunks, read [Session.text], stop when [Session.done].
 */
class SpeechToText(context: Context, dir: File? = null) : AutoCloseable {
    private val modelDir = dir ?: overrideDir(context).takeIf { File(it, "tokens.txt").isFile }
    val modelName: String = modelDir?.name ?: "built-in Kroko en"

    private val recognizer = OnlineRecognizer(
        if (modelDir == null) context.assets else null,
        OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = path(modelDir, "encoder"),
                    decoder = path(modelDir, "decoder"),
                    joiner = path(modelDir, "joiner"),
                ),
                tokens = modelDir?.let { File(it, "tokens.txt").path } ?: "$DIR/tokens.txt",
                numThreads = 2,
            ),
            endpointConfig = EndpointConfig(
                // nothing said yet: give up after ~3 s (this timer also counts the lead silence + pre-roll)
                rule1 = EndpointRule(false, 5.0f, 0f),
                // something said: stop after 0.8 s of silence
                rule2 = EndpointRule(true, 0.8f, 0f),
                // hard limit
                rule3 = EndpointRule(false, 0f, MAX_SECONDS),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    /** [preRoll]: audio from just before the wake, so a command said right after "Hey Jimi" is not cut. */
    fun session(preRoll: ShortArray? = null) = Session(recognizer.createStream()).also {
        // the model drops the first words if speech starts at sample 0
        it.accept(ShortArray(LEAD_SILENCE))
        if (preRoll != null) it.accept(preRoll)
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
            text = normalize(recognizer.getResult(stream).text)
            if (recognizer.isEndpoint(stream)) done = true
        }

        override fun close() = stream.release()
    }

    override fun close() = recognizer.release()

    companion object {
        private const val DIR = "stt"

        fun overrideDir(context: Context) = File(context.getExternalFilesDir(null), "models/stt")

        /** Lower case, no punctuation (Kroko writes "Hey, Jimmy."), keeps apostrophes: "what's". */
        fun normalize(text: String) =
            text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Prefer int8 files when a folder has both. */
        private fun path(dir: File?, part: String): String {
            if (dir == null) return "$DIR/$part.onnx"
            val files = dir.listFiles { f -> f.name.startsWith(part) && f.name.endsWith(".onnx") }.orEmpty()
            val f = files.firstOrNull { it.name.contains("int8") } ?: files.firstOrNull() ?: error("no $part*.onnx in $dir")
            return f.path
        }
        const val SAMPLE_RATE = 16_000
        const val MAX_SECONDS = 10f
        private const val LEAD_SILENCE = SAMPLE_RATE / 2
    }
}

package com.alrasin.jarvis.voice

import android.content.Context
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
 *
 * Wake text is removed by phrase, preserving command words overlapping the wake detector's trigger.
 * [CaptureEndpoint] combines audio activity and partial text for adaptive command ending.
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
            enableEndpoint = false,
            decodingMethod = "greedy_search",
        ),
    )

    /**
     * [preRoll]: audio from just before the wake, so a command said right after "Hey Jimi" is not cut.
     * [waitStart]: seconds to wait for the user to start talking.
     */
    fun session(
        preRoll: ShortArray? = null,
        waitStart: Float = WAIT_START,
        stripWake: Boolean = preRoll != null,
    ) = Session(
        recognizer.createStream(),
        waitStart = waitStart,
        stripWake = stripWake,
    ).also {
            // the model drops the first words if speech starts at sample 0
            it.feed(ShortArray(LEAD_SILENCE), LEAD_SILENCE)
            if (preRoll != null) it.feed(preRoll, preRoll.size)
        }

    inner class Session(
        private val stream: OnlineStream,
        waitStart: Float,
        private val stripWake: Boolean,
    ) : AutoCloseable {
        /** Command text (wake phrase removed). */
        var text = ""
            private set
        /** Everything the model heard, wake phrase included. */
        var raw = ""
            private set
        val finishReason: CaptureEndpoint.FinishReason? get() = endpoint.finishReason
        val done: Boolean get() = finishReason != null
        private val endpoint = CaptureEndpoint(
            waitStartMillis = (waitStart * 1_000).toLong(),
            maxMillis = (MAX_SECONDS * 1_000).toLong(),
        )
        private val activity = CaptureVoiceActivity()

        fun accept(chunk: ShortArray, count: Int = chunk.size) {
            if (done) return
            require(count in 0..chunk.size)
            feed(chunk, count)
            if (endpoint.accept(count, text, activity.hasSpeech(chunk, count)) != null) {
                // Right-context padding flushes the final decoder frames, including at the hard limit.
                stream.acceptWaveform(FloatArray(LEAD_SILENCE), SAMPLE_RATE)
                stream.inputFinished()
                decode()
            }
        }

        internal fun feed(chunk: ShortArray, count: Int) {
            stream.acceptWaveform(FloatArray(count) { chunk[it] / 32768f }, SAMPLE_RATE)
            decode()
        }

        private fun decode() {
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            raw = normalize(r.text)
            text = if (stripWake) WakePhrase.strip(raw) else raw
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
        const val MAX_SECONDS = 20f
        const val WAIT_START = 7f
        private const val LEAD_SILENCE = SAMPLE_RATE / 2
    }
}

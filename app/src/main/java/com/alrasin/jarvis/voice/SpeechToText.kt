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
 *
 * End of command (D-027, like phone assistants): wait up to [Session] `waitStart` seconds for the command to start,
 * then stop after [END_SILENCE] s of silence. Words from the pre-roll (the "Hey Jimi" itself) are dropped by
 * their timestamps, so they neither show up in the text nor count as "the user already spoke".
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
                // the "nothing said yet" and hard limits are done in Session (they must ignore the wake words)
                rule1 = EndpointRule(false, 60f, 0f),
                // something said: silence this long ends the command (only used once command words exist)
                rule2 = EndpointRule(true, END_SILENCE, 0f),
                rule3 = EndpointRule(false, 0f, 60f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ),
    )

    /**
     * [preRoll]: audio from just before the wake, so a command said right after "Hey Jimi" is not cut.
     * [waitStart]: seconds to wait for the user to start talking.
     */
    fun session(preRoll: ShortArray? = null, waitStart: Float = WAIT_START) = Session(
        recognizer.createStream(),
        startAt = (LEAD_SILENCE + (preRoll?.size ?: 0)) / SAMPLE_RATE.toFloat(),
        cut = (LEAD_SILENCE + (preRoll?.size ?: 0)) / SAMPLE_RATE.toFloat() + if (preRoll != null) WAKE_MARGIN else 0f,
        waitStart = waitStart,
    ).also {
            // the model drops the first words if speech starts at sample 0
            it.feed(ShortArray(LEAD_SILENCE), LEAD_SILENCE)
            if (preRoll != null) it.feed(preRoll, preRoll.size)
        }

    /** [startAt]: stream time (s) where live audio starts. Words starting before [cut] are the wake phrase. */
    inner class Session(
        private val stream: OnlineStream,
        private val startAt: Float,
        private val cut: Float,
        private val waitStart: Float,
    ) : AutoCloseable {
        /** Command text (wake phrase removed). */
        var text = ""
            private set
        /** Everything the model heard, wake phrase included. */
        var raw = ""
            private set
        var done = false
            private set
        private var samples = 0L

        fun accept(chunk: ShortArray, count: Int = chunk.size) {
            if (done) return
            feed(chunk, count)
            val elapsed = samples / SAMPLE_RATE.toFloat() - startAt
            done = when {
                elapsed >= MAX_SECONDS -> true
                text.isEmpty() -> elapsed >= waitStart
                else -> recognizer.isEndpoint(stream)
            }
        }

        internal fun feed(chunk: ShortArray, count: Int) {
            stream.acceptWaveform(FloatArray(count) { chunk[it] / 32768f }, SAMPLE_RATE)
            samples += count
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            val r = recognizer.getResult(stream)
            raw = normalize(r.text)
            text = commandText(r.tokens, r.timestamps, cut)
        }

        override fun close() = stream.release()
    }

    override fun close() = recognizer.release()

    companion object {
        private const val DIR = "stt"

        fun overrideDir(context: Context) = File(context.getExternalFilesDir(null), "models/stt")

        /**
         * Joins [tokens] into words (a token starting with a space starts a word), drops words that start before
         * [cut] (wake phrase in the pre-roll), then strips a wake phrase the user may say again.
         */
        fun commandText(tokens: Array<String>, times: FloatArray, cut: Float): String {
            val sb = StringBuilder()
            var keep = false
            for (i in tokens.indices) {
                val t = tokens[i]
                val newWord = i == 0 || t.startsWith(" ") || t.startsWith("\u2581")
                if (newWord) keep = (times.getOrElse(i) { Float.MAX_VALUE }) >= cut
                if (keep) sb.append(if (newWord) " " + t.trimStart(' ', '\u2581') else t)
            }
            return WakePhrase.strip(normalize(sb.toString()))
        }

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
        const val END_SILENCE = 1.2f
        private const val WAKE_MARGIN = 0.35f
        private const val LEAD_SILENCE = SAMPLE_RATE / 2
    }
}

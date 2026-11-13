package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File

/**
 * Bangla mode (ROADMAP #47, D-075): Whisper small hears Bangla or mixed Bangla + English after the wake word and
 * gives English text, so the rules and tools (English) keep working. Slow on this phone, so only used when the
 * user turns Bangla mode on; the English streaming text is kept while it loads or if it is too slow.
 */
class BanglaSpeechToText(context: Context) : AutoCloseable {
    private val context = context.applicationContext
    private var recognizer: OfflineRecognizer? = null
    private val decoder = BoundedSpeechDecoder(
        decode = ::decode,
        release = { recognizer?.release(); recognizer = null },
        deadlineMillis = BanglaSpeech.DEADLINE_MS,
        prepare = {
            val started = System.nanoTime()
            recognizer = load()
            JarvisLog.i("Bangla STT loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        },
        onFallback = { JarvisLog.i("Bangla STT fallback: $it") },
    )

    suspend fun hear(audio: ShortArray, streamingText: String, enabled: Boolean, stripWake: Boolean): String {
        if (!BanglaSpeech.use(enabled, PortableModels.banglaReady(context), audio.size)) return streamingText
        // Streaming English text is often empty for Bangla speech; give the decoder a non-blank fallback.
        val decoded = decoder.refine(audio, streamingText.ifBlank { " " }, true)
        if (decoded.isBlank() || decoded == streamingText) return streamingText
        val cleaned = BanglaSpeech.clean(decoded) ?: return streamingText
        val normalized = SpeechToText.normalize(cleaned)
        return (if (stripWake) WakePhrase.strip(normalized) else normalized).ifBlank { streamingText }
    }

    /** Benchmark only (#48): load now so load time is measured apart from decode time. */
    fun loadForBench() {
        if (recognizer == null) recognizer = load()
    }

    /** Benchmark only (#48): load if needed, decode with no deadline, return cleaned English ("" if junk). */
    fun decodeForBench(audio: ShortArray): String {
        loadForBench()
        return BanglaSpeech.clean(decode(audio))?.let(SpeechToText::normalize).orEmpty()
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
            JarvisLog.i("Bangla STT decode ${(System.nanoTime() - started) / 1_000_000} ms, audio ${audio.size * 1_000L / SpeechToText.SAMPLE_RATE} ms")
        }
    }

    private fun load(): OfflineRecognizer {
        val dir = PortableModels.dir(context, "stt_bangla")
        fun path(name: String) = File(dir, name).path
        return OfflineRecognizer(null, OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SpeechToText.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = path(BanglaSpeech.ENCODER),
                    decoder = path(BanglaSpeech.DECODER),
                    language = "bn",
                    task = "translate",
                ),
                tokens = path(BanglaSpeech.TOKENS), numThreads = 4, provider = "cpu",
            ),
        ))
    }

    override fun close() = decoder.close()
}

/** Pure parts of Bangla mode, unit tested. */
object BanglaSpeech {
    const val ENCODER = "small-encoder.int8.onnx"
    const val DECODER = "small-decoder.int8.onnx"
    const val TOKENS = "small-tokens.txt"
    val FILES = listOf(ENCODER, DECODER, TOKENS)
    const val DEADLINE_MS = 10_000L
    /** Whisper reads 30 s windows; longer commands are cut, so skip them. Under 0.4 s is noise. */
    private const val MIN_SAMPLES = SpeechToText.SAMPLE_RATE * 4 / 10
    private const val MAX_SAMPLES = SpeechToText.SAMPLE_RATE * 28

    fun use(enabled: Boolean, ready: Boolean, samples: Int): Boolean =
        enabled && ready && samples in MIN_SAMPLES..MAX_SAMPLES

    private val TAGS = Regex("\\[[^]]*]|\\([^)]*\\)|♪+")
    /** Lines Whisper makes up on silence or noise. */
    private val MADE_UP = listOf(
        "thank you for watching", "thanks for watching", "please subscribe", "like and subscribe", "see you in the next video",
    )

    /** Whisper text → command text, or null when it is only tags or a made-up line. */
    fun clean(text: String): String? {
        val t = text.replace(TAGS, " ").replace(Regex("\\s+"), " ").trim().trim('-', '—', ' ')
        if (t.isEmpty()) return null
        val lower = t.lowercase()
        if (lower.trimEnd('.') == "you" || MADE_UP.any { lower.contains(it) }) return null
        return repeatCut(t)
    }

    /** Whisper can loop ("call mom call mom call mom …"); keep the first copy. */
    internal fun repeatCut(text: String): String {
        val words = text.split(' ')
        for (size in 1..words.size / 3) {
            val head = words.take(size)
            if ((size until words.size).all { words[it].equals(head[it % size], true) }) return head.joinToString(" ")
        }
        return text
    }
}

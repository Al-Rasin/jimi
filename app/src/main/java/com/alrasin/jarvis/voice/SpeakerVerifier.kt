package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Owner voice check (PLAN 12.1): 3D-Speaker CAM++ embeddings (asset `speaker/campplus.onnx`, tools/fetch_speech.sh).
 * Enrolment keeps one embedding per recording in `files/speaker/owner.bin`; a command matches when its cosine
 * similarity to the mean of those is ≥ `Settings.speakerThreshold`.
 * Everything returns null when the model is missing or nobody is enrolled, so callers can tell "unknown" from "no".
 */
@Singleton
class SpeakerVerifier @Inject constructor(@ApplicationContext private val context: Context) {
    private val file = File(context.filesDir, "speaker/owner.bin")
    private val extractor: SpeakerEmbeddingExtractor? by lazy {
        if (context.assets.list("speaker")?.contains("campplus.onnx") != true) {
            JarvisLog.i("voice ID model missing (run tools/fetch_speech.sh)")
            return@lazy null
        }
        runCatching {
            SpeakerEmbeddingExtractor(context.assets, SpeakerEmbeddingExtractorConfig("speaker/campplus.onnx", 2, false, "cpu"))
        }.onFailure { JarvisLog.e("voice ID model failed to load", it) }.getOrNull()
    }
    private var owner: List<FloatArray> = load()

    val available: Boolean get() = extractor != null
    val samples: Int get() = owner.size

    /** Normalized embedding of 16 kHz audio, or null (no model / too short). */
    @Synchronized
    fun embed(audio: ShortArray): FloatArray? {
        if (audio.size < MIN_SAMPLES) return null
        val ex = extractor ?: return null
        val stream = ex.createStream()
        return try {
            stream.acceptWaveform(FloatArray(audio.size) { audio[it] / 32768f }, SpeechToText.SAMPLE_RATE)
            stream.inputFinished()
            if (ex.isReady(stream)) normalize(ex.compute(stream)) else null
        } finally {
            stream.release()
        }
    }

    /** Adds one recording of the owner. Returns false if it was too short or the model is missing. */
    fun enroll(audio: ShortArray): Boolean {
        val e = embed(audio) ?: return false
        owner = owner + e
        save(owner)
        JarvisLog.i("voice ID: enrolled sample ${owner.size}")
        return true
    }

    fun reset() {
        owner = emptyList()
        file.delete()
    }

    /** Similarity to the owner (−1..1), or null if unknown. */
    fun score(audio: ShortArray): Float? {
        if (owner.isEmpty()) return null
        val e = embed(audio) ?: return null
        return cosine(e, mean(owner))
    }

    private fun load(): List<FloatArray> = runCatching {
        if (!file.isFile) return emptyList()
        DataInputStream(file.inputStream().buffered()).use { inp ->
            val n = inp.readInt()
            val dim = inp.readInt()
            List(n) { FloatArray(dim) { inp.readFloat() } }
        }
    }.onFailure { JarvisLog.e("voice ID: could not read enrolment", it) }.getOrDefault(emptyList())

    private fun save(list: List<FloatArray>) {
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { out ->
            out.writeInt(list.size)
            out.writeInt(list.first().size)
            list.forEach { e -> e.forEach(out::writeFloat) }
        }
    }

    companion object {
        /** 1 s: shorter clips give unstable embeddings. */
        const val MIN_SAMPLES = SpeechToText.SAMPLE_RATE

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return if (n == 0f) v else FloatArray(v.size) { v[it] / n }
        }

        fun mean(list: List<FloatArray>): FloatArray =
            normalize(FloatArray(list.first().size) { i -> list.sumOf { it[i].toDouble() }.toFloat() / list.size })

        fun cosine(a: FloatArray, b: FloatArray): Float {
            var dot = 0.0
            for (i in a.indices) dot += a[i] * b[i]
            return (dot / (norm(a) * norm(b)).coerceAtLeast(1e-9)).toFloat()
        }

        private fun norm(v: FloatArray) = sqrt(v.sumOf { (it * it).toDouble() })
    }
}

package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

/** Speech engines Jimi can use: sherpa-onnx models, or the phone's own engine (D-053). */
enum class Engine { PIPER, SUPERTONIC, ANDROID }

/**
 * One downloadable model. A pack can hold many voices (Supertonic) or one (Piper).
 * Files come from the sherpa-onnx model mirrors and go to `files/voices/<id>/` (not in the APK).
 */
data class VoicePack(
    val id: String,
    val engine: Engine,
    val sizeMb: Int,
    val repo: String,
    val files: List<String>,
    /** If set: one .tar.bz2 from the sherpa-onnx GitHub release instead of single files from [repo]. */
    val archive: String? = null,
) {
    /** The model file (first in [files]). */
    val model: String get() = files.first()
}

/** A voice the user can pick. [sid] = speaker number inside the pack. */
data class Voice(val id: String, val name: String, val male: Boolean, val accent: String, val pack: VoicePack, val sid: Int = 0) {
    val label: String get() = "$name · ${if (male) "Male" else "Female"} · $accent"
}

/**
 * All voices (D-038). Supertonic = natural (about real time on the phone), Piper = fast and plainer.
 * Kokoro and Kitten were tested and dropped: 6.5x and 1.3x slower than real time on the Helio G88.
 * "piper-alan" is inside the APK (assets `tts/`), the rest are downloaded in Settings → Voice.
 */
object Voices {
    private const val HF = "https://huggingface.co/csukuangfj"
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"
    const val BUILT_IN = "piper-alan"

    val SUPERTONIC = VoicePack(
        "supertonic", Engine.SUPERTONIC, 128, "",
        listOf(
            "vector_estimator.int8.onnx", "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vocoder.int8.onnx",
            "tts.json", "unicode_indexer.bin", "voice.bin",
        ),
        archive = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    )
    private val ALAN = VoicePack(BUILT_IN, Engine.PIPER, 80, "", listOf("en_GB-alan-medium.onnx", "tokens.txt"))
    private val fallback = Voice(BUILT_IN, "Alan", true, "UK", ALAN)

    /** Voices of the phone's speech engine are saved as "google:<engine voice name>" (D-053). */
    const val ANDROID_PREFIX = "google:"
    val ANDROID_PACK = VoicePack("android", Engine.ANDROID, 0, "", listOf(""))

    /**
     * The Google voices kept after scoring all 14 installed ones on the phone (D-054): highest naturalness
     * (UTMOS 4.43–4.47 vs Piper Alan 4.02), ~6x faster than Piper. Gender checked by measured pitch.
     */
    val PHONE: List<Voice> = listOf(
        Voice(ANDROID_PREFIX + "en-us-x-iob-local", "Iris (Siri-like)", false, "US", ANDROID_PACK),   // closest to Siri US Voice 2
        Voice(ANDROID_PREFIX + "en-us-x-tpf-local", "Tara", false, "US", ANDROID_PACK),
        Voice(ANDROID_PREFIX + "en-us-x-iom-local", "Max", true, "US", ANDROID_PACK),
        Voice(ANDROID_PREFIX + "en-gb-x-rjs-local", "Rhys", true, "UK", ANDROID_PACK),
    )
    val DEFAULT: String = PHONE.first().id

    /** The picker: the Google voices, plus built-in Alan for phones without Google speech services. */
    val ALL: List<Voice> = PHONE + fallback

    /** Unknown or removed voices (Amy, Ryan, Supertonic, other Google voices) become the default voice. */
    fun byId(id: String?): Voice = ALL.firstOrNull { it.id == id } ?: PHONE.first()

    /** Any Google voice by engine name, for the voice benchmark only (e.g. "en-us-x-iol-local"). */
    fun android(engineName: String): Voice =
        Voice(ANDROID_PREFIX + engineName, engineName, false, "EN", ANDROID_PACK)

    fun dir(context: Context, pack: VoicePack) = File(context.getExternalFilesDir(null) ?: context.filesDir, "voices/${pack.id}")

    fun installed(context: Context, pack: VoicePack): Boolean =
        if (pack.engine == Engine.ANDROID) true
        else if (pack.id == BUILT_IN) PortableModels.voiceReady(context)
        else pack.files.all { ModelFiles.present(File(dir(context, pack), it)) }

    /** Downloads the missing files of [pack]; [progress] gets 0..1. Throws on network errors (partial files are removed). */
    suspend fun download(context: Context, pack: VoicePack, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        if (pack.id == BUILT_IN) {
            PortableModels.download(context, "tts", progress)
            return@withContext
        }
        val dir = dir(context, pack).apply { mkdirs() }
        val archive = pack.archive
        if (archive != null) {
            val part = File(dir, "$archive.tar.bz2.part")
            try {
                Downloader.fetch("$RELEASES/$archive.tar.bz2", part) { progress(it * 0.9f) }
                extract(part, dir, pack.files.toSet())
                progress(1f)
            } finally {
                part.delete()
            }
            check(installed(context, pack)) { "archive is missing files" }
        } else {
            pack.files.forEachIndexed { i, name ->
                val dest = File(dir, name)
                if (ModelFiles.present(dest)) return@forEachIndexed
                Downloader.fetch("$HF/${pack.repo}/resolve/main/$name", dest) { progress((i + it) / pack.files.size) }
            }
        }
        JarvisLog.i("voice pack ${pack.id} downloaded")
    }

    /** Takes only the [wanted] files (by name, folders flattened) out of a .tar.bz2. */
    private fun extract(tarBz2: File, dir: File, wanted: Set<String>) {
        TarArchiveInputStream(BZip2CompressorInputStream(tarBz2.inputStream().buffered())).use { tar ->
            while (true) {
                val e = tar.nextEntry ?: break
                val name = e.name.substringAfterLast('/')
                if (e.isDirectory || name !in wanted) continue
                val part = File(dir, "$name.part")
                part.outputStream().use { tar.copyTo(it) }
                check(part.renameTo(File(dir, name))) { "could not save $name" }
            }
        }
    }

    fun delete(context: Context, pack: VoicePack) {
        if (pack.id != BUILT_IN && pack.engine != Engine.ANDROID) dir(context, pack).deleteRecursively()
    }
}

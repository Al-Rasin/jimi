package com.alrasin.jarvis.voice

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

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

    private fun piper(name: String, lang: String) =
        VoicePack("piper-$name", Engine.PIPER, 63, "vits-piper-$lang-$name-medium", listOf("$lang-$name-medium.onnx", "tokens.txt"))

    /** Speaker numbers come from the model metadata (id2speaker). */
    val ALL: List<Voice> = listOf(
        // fix8: Supertonic is too slow on G88; saved selections fall back to Alan.
        fallback,
        Voice("piper-amy", "Amy", false, "US", piper("amy", "en_US")),
        Voice("piper-ryan", "Ryan", true, "US", piper("ryan", "en_US")),
    )

    /** Voices of the phone's speech engine are saved as "google:<engine voice name>" (D-053). */
    const val ANDROID_PREFIX = "google:"
    val ANDROID_PACK = VoicePack("android", Engine.ANDROID, 0, "", listOf(""))

    fun byId(id: String?): Voice =
        if (id != null && id.startsWith(ANDROID_PREFIX)) android(id.removePrefix(ANDROID_PREFIX))
        else ALL.firstOrNull { it.id == id } ?: fallback

    /** e.g. "en-us-x-iol-local" → "Google iol", Male, US. Gender comes from a known-name table (unknown = female). */
    fun android(engineName: String): Voice {
        val parts = engineName.lowercase().split('-')
        val accent = parts.getOrNull(1)?.uppercase()?.let { if (it == "GB") "UK" else it } ?: "EN"
        val code = parts.getOrNull(3) ?: engineName
        return Voice(ANDROID_PREFIX + engineName, "Google $code", code in GOOGLE_MALE, accent, ANDROID_PACK)
    }

    private val GOOGLE_MALE = setOf("iol", "iom", "tpd", "gbb", "gbd", "rjs", "end", "aub", "aud", "cxx")

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
                fetch("$RELEASES/$archive.tar.bz2", part) { progress(it * 0.9f) }
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
                val part = File(dir, "$name.part")
                try {
                    fetch("$HF/${pack.repo}/resolve/main/$name", part) { progress((i + it) / pack.files.size) }
                    check(part.renameTo(dest)) { "could not save $name" }
                } finally {
                    part.delete()
                }
            }
        }
        JarvisLog.i("voice pack ${pack.id} downloaded")
    }

    private fun fetch(url: String, dest: File, progress: (Float) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        try {
            check(conn.responseCode == HttpURLConnection.HTTP_OK) { "Download failed: HTTP ${conn.responseCode}" }
            val expected = conn.contentLengthLong
            val total = expected.coerceAtLeast(1)
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total).coerceAtMost(1f))
                    }
                    ModelFiles.validateDownload(done, expected)
                }
            }
        } finally {
            conn.disconnect()
        }
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

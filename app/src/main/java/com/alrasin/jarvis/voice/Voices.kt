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

/** Speech engines in sherpa-onnx that Jimi can use. */
enum class Engine { PIPER, SUPERTONIC }

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
    private val ALAN = VoicePack(BUILT_IN, Engine.PIPER, 0, "", listOf("en_GB-alan-medium.onnx", "tokens.txt"))

    private fun piper(name: String, lang: String) =
        VoicePack("piper-$name", Engine.PIPER, 63, "vits-piper-$lang-$name-medium", listOf("$lang-$name-medium.onnx", "tokens.txt"))

    /** Speaker numbers come from the model metadata (id2speaker). */
    val ALL: List<Voice> = listOf(
        // Supertonic voice.bin = F1..F5, M1..M5 (sorted file names)
        Voice("supertonic-f1", "Sophia", false, "US", SUPERTONIC, 0),
        Voice("supertonic-f2", "Olivia", false, "US", SUPERTONIC, 1),
        Voice("supertonic-f3", "Mia", false, "US", SUPERTONIC, 2),
        Voice("supertonic-f4", "Ava", false, "US", SUPERTONIC, 3),
        Voice("supertonic-f5", "Zoe", false, "US", SUPERTONIC, 4),
        Voice("supertonic-m1", "James", true, "US", SUPERTONIC, 5),
        Voice("supertonic-m2", "Ethan", true, "US", SUPERTONIC, 6),
        Voice("supertonic-m3", "Noah", true, "US", SUPERTONIC, 7),
        Voice("supertonic-m4", "Lucas", true, "US", SUPERTONIC, 8),
        Voice("supertonic-m5", "Owen", true, "US", SUPERTONIC, 9),
        Voice(BUILT_IN, "Alan", true, "UK", ALAN),
        Voice("piper-ryan", "Ryan", true, "US", piper("ryan", "en_US")),
        Voice("piper-joe", "Joe", true, "US", piper("joe", "en_US")),
        Voice("piper-hfc_male", "Hank", true, "US", piper("hfc_male", "en_US")),
        Voice("piper-northern_english_male", "Arthur", true, "UK", piper("northern_english_male", "en_GB")),
        Voice("piper-amy", "Amy", false, "US", piper("amy", "en_US")),
        Voice("piper-kristin", "Kristin", false, "US", piper("kristin", "en_US")),
        Voice("piper-hfc_female", "Hannah", false, "US", piper("hfc_female", "en_US")),
        Voice("piper-jenny_dioco", "Jenny", false, "UK", piper("jenny_dioco", "en_GB")),
        Voice("piper-cori", "Cori", false, "UK", piper("cori", "en_GB")),
    )

    fun byId(id: String?): Voice = ALL.firstOrNull { it.id == id } ?: ALL.first { it.id == BUILT_IN }

    fun dir(context: Context, pack: VoicePack) = File(context.getExternalFilesDir(null) ?: context.filesDir, "voices/${pack.id}")

    fun installed(context: Context, pack: VoicePack): Boolean =
        pack.id == BUILT_IN || pack.files.all { File(dir(context, pack), it).isFile }

    /** Downloads the missing files of [pack]; [progress] gets 0..1. Throws on network errors (partial files are removed). */
    suspend fun download(context: Context, pack: VoicePack, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
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
                if (dest.isFile) return@forEachIndexed
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
            val total = conn.contentLengthLong.coerceAtLeast(1)
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
        if (pack.id != BUILT_IN) dir(context, pack).deleteRecursively()
    }
}

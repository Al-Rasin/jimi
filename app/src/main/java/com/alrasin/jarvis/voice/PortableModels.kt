package com.alrasin.jarvis.voice

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

// Regexes built once, not on every call (ROADMAP §L.4).
private val IMPORT_LLM_RE = Regex("[A-Za-z0-9_.-]+\\.gguf")

data class ModelStatus(
    val id: String, val label: String, val ready: Boolean, val sizeLabel: String,
    val downloadable: Boolean, val detail: String,
)

object PortableModels {
    private const val RELEASES = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val WAKE_RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    private val lock = Mutex()
    private val wakeFeatures = listOf("melspectrogram.onnx", "embedding_model.onnx")
    private val speechFiles = setOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")
    private val voiceFiles = setOf("en_GB-alan-medium.onnx", "tokens.txt")
    private val secondPassFiles = setOf("preprocess.onnx", "encode.int8.onnx", "uncached_decode.int8.onnx", "cached_decode.int8.onnx", "tokens.txt")

    fun secondPassReady(context: Context): Boolean =
        secondPassFiles.all { ModelFiles.present(File(dir(context, "stt_second_pass"), it)) }

    fun dir(context: Context, name: String) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "models/$name")

    fun asset(context: Context, path: String): Boolean = runCatching {
        context.assets.open(path).use { it.read() >= 0 }
    }.getOrDefault(false)

    fun present(context: Context, group: String, name: String) =
        ModelFiles.present(File(dir(context, group), name)) || asset(context, "$group/$name")

    fun voiceReady(context: Context): Boolean =
        ModelFiles.voiceReady(dir(context, "tts")) ||
            (voiceFiles.all { asset(context, "tts/$it") } && asset(context, "tts/espeak-ng-data/phontab"))

    fun status(context: Context): List<ModelStatus> = listOf(
        ModelStatus("wake_features", "Wake word engine", wakeFeatures.all { present(context, "wakeword", it) },
            "About 3 MB", true, "Shared openWakeWord features, stored on this phone."),
        ModelStatus("wake_jimi", "Hey Jimi", present(context, "wakeword", "hey_jimi.onnx"),
            "Custom model", false, "Included in release APKs, or import your trained hey_jimi.onnx. No public download is configured."),
        ModelStatus("stt", "Speech recognition", ModelFiles.speechReady(dir(context, "stt")) || speechFiles.all { asset(context, "stt/$it") },
            "About 70 MB installed", true, "Kroko English; works offline after download."),
        ModelStatus("tts", "Alan voice and pronunciation data", voiceReady(context),
            "About 80 MB installed", true, "Other voices can be selected in the next step."),
        ModelStatus("stt_second_pass", "Second-pass speech (optional)", secondPassReady(context),
            "About 124 MB installed", true, "Experimental Moonshine tiny int8. Enable in Conversation after download; falls back while loading or if slower than 1.5 seconds."),
        ModelStatus("speaker", "Voice ID (optional)", present(context, "speaker", "campplus.onnx"),
            "About 28 MB", true, "Enroll your voice in Settings after installing. Restart Jimi after adding this model."),
        ModelStatus("llm", "Local chat and tool models (optional)",
            com.alrasin.jarvis.llm.Llama.available && dir(context, "llm").listFiles().orEmpty().let { files ->
                files.any { it.name.startsWith("functiongemma", true) && it.extension == "gguf" && ModelFiles.present(it) } &&
                    files.any { (it.name.startsWith("qwen3", true) || it.name.startsWith("gemma-3", true) || it.name.startsWith("LFM2.5-1.2B-Instruct-", true)) && it.extension == "gguf" && ModelFiles.present(it) }
            }, "About 300–800 MB per model", false,
            "Import FunctionGemma and Qwen3/Gemma 3 GGUF files, or select imported LFM2.5 in Settings. Rules still work without them. " +
                if (com.alrasin.jarvis.llm.Llama.available) "Local AI library available." else "This APK also needs the native local AI library."),
    )

    suspend fun download(context: Context, id: String, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        lock.withLock {
            when (id) {
                "wake_features" -> wakeFeatures.forEachIndexed { index, name ->
                    if (!present(context, "wakeword", name)) fetch("$WAKE_RELEASE/$name", File(dir(context, "wakeword"), name)) {
                        progress((index + it) / wakeFeatures.size)
                    }
                }
                "stt" -> archive(context, "$RELEASES/asr-models/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06.tar.bz2", "stt", progress) { it in speechFiles }
                "stt_second_pass" -> archive(context, "$RELEASES/asr-models/sherpa-onnx-moonshine-tiny-en-int8.tar.bz2", "stt_second_pass", progress) { it in secondPassFiles }
                "tts" -> archive(context, "$RELEASES/tts-models/vits-piper-en_GB-alan-medium.tar.bz2", "tts", progress) {
                    it in voiceFiles || it.startsWith("espeak-ng-data/")
                }
                "speaker" -> fetch("$RELEASES/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx", File(dir(context, "speaker"), "campplus.onnx"), progress)
                else -> error("This model must be supplied with the APK or imported")
            }
            check(status(context).first { it.id == id }.ready) { "The model package is incomplete" }
            progress(1f)
        }
    }

    suspend fun importWake(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val destination = File(dir(context, "wakeword"), "hey_jimi.onnx")
            destination.parentFile!!.mkdirs()
            val part = File.createTempFile("wake-", ".part", destination.parentFile)
            try {
                context.contentResolver.openInputStream(uri)?.use { copy(it, part, 16L * 1024 * 1024) }
                    ?: error("Could not read the selected model")
                OrtSession.SessionOptions().use { options ->
                    options.setIntraOpNumThreads(1)
                    OrtEnvironment.getEnvironment().createSession(part.path, options).use { session ->
                        val shape = (session.inputInfo.values.singleOrNull()?.info as? TensorInfo)?.shape
                        require(shape != null && shape.size == 3 && shape[1] == 16L && shape[2] == 96L) {
                            "Choose a Hey Jimi openWakeWord model with 16 × 96 input features"
                        }
                    }
                }
                check(part.renameTo(destination)) { "Could not save the wake model" }
            } finally { part.delete() }
        }
    }

    suspend fun importLlm(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        lock.withLock {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: error("The selected file has no name")
            require(name.matches(IMPORT_LLM_RE) &&
                listOf("functiongemma", "qwen3", "gemma-3", "LFM2.5-1.2B-Instruct-").any { name.startsWith(it, true) }) {
                "Select a FunctionGemma, Qwen3, Gemma-3 or LFM2.5-1.2B-Instruct .gguf model"
            }
            val destination = File(dir(context, "llm"), name)
            destination.parentFile!!.mkdirs()
            val part = File.createTempFile("llm-", ".part", destination.parentFile)
            try {
                context.contentResolver.openInputStream(uri)?.use { copy(it, part, 2L * 1024 * 1024 * 1024) }
                    ?: error("Could not read the selected model")
                val magic = java.io.DataInputStream(part.inputStream()).use { input -> ByteArray(4).also(input::readFully) }
                require(magic.contentEquals(byteArrayOf(0x47, 0x47, 0x55, 0x46))) { "This file is not a GGUF model" }
                check(part.renameTo(destination)) { "Could not save the model" }
            } finally { part.delete() }
        }
    }

    private suspend fun archive(context: Context, url: String, group: String, progress: (Float) -> Unit, wanted: (String) -> Boolean) {
        val packed = File.createTempFile("jimi-model-", ".tar.bz2", context.cacheDir)
        try {
            fetch(url, packed) { progress(it * 0.8f) }
            TarArchiveInputStream(BZip2CompressorInputStream(packed.inputStream().buffered())).use { tar ->
                var expanded = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextTarEntry ?: break
                    val name = ModelFiles.archivePath(entry.name) ?: continue
                    if (!entry.isFile || !wanted(name)) continue
                    expanded += entry.size
                    check(expanded <= 250L * 1024 * 1024) { "Model archive is too large" }
                    val destination = File(dir(context, group), name)
                    if (ModelFiles.present(destination)) continue
                    destination.parentFile!!.mkdirs()
                    val part = File.createTempFile("model-", ".part", destination.parentFile)
                    try {
                        val bytes = copy(tar, part, 250L * 1024 * 1024)
                        ModelFiles.validateDownload(bytes, entry.size)
                        check(part.renameTo(destination)) { "Could not save $name" }
                    } finally { part.delete() }
                }
            }
            if (group == "tts") File(dir(context, group), ".complete").writeText("complete")
        } finally { packed.delete() }
    }

    private suspend fun fetch(url: String, destination: File, progress: (Float) -> Unit) {
        destination.parentFile!!.mkdirs()
        val part = File.createTempFile("download-", ".part", destination.parentFile)
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            check(connection.responseCode in 200..299) { "Download failed (HTTP ${connection.responseCode})" }
            check(connection.url.protocol == "https") { "A secure download is required" }
            val expected = connection.contentLengthLong
            val bytes = connection.inputStream.use { input ->
                copy(input, part, 250L * 1024 * 1024) { done ->
                    if (expected > 0) progress((done.toFloat() / expected).coerceIn(0f, 1f))
                }
            }
            ModelFiles.validateDownload(bytes, expected)
            check(part.renameTo(destination)) { "Could not save the download" }
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    private suspend fun copy(input: InputStream, file: File, limit: Long, progress: (Long) -> Unit = {}): Long {
        var total = 0L
        file.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= limit) { "Model exceeds the supported download size" }
                output.write(buffer, 0, count)
                progress(total)
            }
        }
        return total
    }
}

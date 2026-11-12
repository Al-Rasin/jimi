package com.alrasin.jarvis.voice

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** One HTTPS file downloader for models and voice packs (ROADMAP §L.7). */
object Downloader {
    /** Biggest single file or archive we accept. */
    const val MAX_BYTES = 250L * 1024 * 1024

    /** Downloads [url] to [destination] through a temp file; [progress] gets 0..1. Throws on errors, leaving no partial file. */
    suspend fun fetch(url: String, destination: File, limit: Long = MAX_BYTES, progress: (Float) -> Unit = {}) {
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
                copy(input, part, limit) { done ->
                    if (expected > 0) progress((done.toFloat() / expected).coerceIn(0f, 1f))
                }
            }
            ModelFiles.validateDownload(bytes, expected)
            if (destination.exists()) destination.delete()
            check(part.renameTo(destination)) { "Could not save the download" }
        } finally {
            connection.disconnect()
            part.delete()
        }
    }

    /** Copies [input] into [file], stopping when cancelled or past [limit] bytes. Returns bytes written. */
    suspend fun copy(input: InputStream, file: File, limit: Long, progress: (Long) -> Unit = {}): Long {
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

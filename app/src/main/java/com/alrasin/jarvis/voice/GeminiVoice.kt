package com.alrasin.jarvis.voice

import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.GeneratedAudio
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A Gemini cloud voice the user can pick (D-053). */
data class CloudVoiceOption(val id: String, val male: Boolean, val style: String) {
    val label: String get() = "$id · ${if (male) "Male" else "Female"} · $style"
}

/**
 * Online voice through Gemini text-to-speech (D-053). Only text that Gemini itself wrote is sent ([CloudSpeech]),
 * so nothing private leaves the phone. Returns null on any error; the caller then uses the phone voice.
 */
class GeminiVoice(private val apiKey: String) {

    fun generate(text: String, voice: String): GeneratedAudio? {
        val spoken = SpeechText.normalize(text)
        if (spoken.isBlank() || System.currentTimeMillis() < pausedUntil) return null
        val models = working?.let { listOf(it) } ?: MODELS
        for (model in models) {
            val t0 = System.currentTimeMillis()
            val req = Request.Builder()
                .url("$BASE$model:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(request(spoken, voice).toRequestBody("application/json".toMediaType()))
                .build()
            val result = runCatching {
                client.newCall(req).execute().use { r ->
                    if (r.code == 429) pausedUntil = System.currentTimeMillis() + PAUSE_MS   // free-key quota
                    if (r.code == 404 || r.code == 400 && working == null) return@use null   // model name not served: try next
                    check(r.isSuccessful) { "Gemini voice HTTP ${r.code}" }
                    parse(r.body?.string().orEmpty())
                }
            }.onFailure { JarvisLog.e("cloud voice failed", it) }.getOrNull()
            if (result != null) {
                working = model
                JarvisLog.i("cloud voice $model: ${result.samples.size * 1000L / RATE} ms audio in ${System.currentTimeMillis() - t0} ms")
                return result
            }
        }
        return null
    }

    companion object {
        /** Newest first; the first that answers is kept for the rest of the run. */
        val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts")
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val RATE = 24_000
        @Volatile private var working: String? = null
        /** After a rate limit (HTTP 429) the phone voice is used for a minute. */
        @Volatile private var pausedUntil = 0L
        private const val PAUSE_MS = 60_000L

        val VOICES = listOf(
            CloudVoiceOption("Sulafat", false, "Warm"),
            CloudVoiceOption("Aoede", false, "Breezy"),
            CloudVoiceOption("Kore", false, "Firm"),
            CloudVoiceOption("Achird", true, "Friendly"),
            CloudVoiceOption("Algieba", true, "Smooth"),
            CloudVoiceOption("Charon", true, "Clear"),
        )
        const val DEFAULT_VOICE = "Sulafat"

        fun byId(id: String?): CloudVoiceOption = VOICES.firstOrNull { it.id == id } ?: VOICES.first()

        private val client by lazy {
            OkHttpClient.Builder().connectTimeout(4, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(1, 5, TimeUnit.MINUTES)).build()
        }

        /** Plain text only: the lite model read a style line ("Say warmly: …") out loud on the phone. */
        fun request(text: String, voice: String): String = Json.write(
            mapOf(
                "contents" to listOf(mapOf("parts" to listOf(mapOf("text" to text)))),
                "generationConfig" to mapOf(
                    "responseModalities" to listOf("AUDIO"),
                    "speechConfig" to mapOf("voiceConfig" to mapOf("prebuiltVoiceConfig" to mapOf("voiceName" to voice))),
                ),
            ),
        )

        /** 16-bit mono PCM at 24 kHz, base64 in the first inline-data part. */
        fun parse(body: String): GeneratedAudio? {
            val root = Json.parse(body) as? Map<*, *> ?: return null
            val first = (root["candidates"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
            val parts = ((first["content"] as? Map<*, *>)?.get("parts") as? List<*>).orEmpty()
            val data = parts.firstNotNullOfOrNull { ((it as? Map<*, *>)?.get("inlineData") as? Map<*, *>)?.get("data") as? String }
                ?: return null
            val bytes = Base64.getDecoder().decode(data)
            val n = bytes.size / 2
            val pcm = FloatArray(n) { i ->
                ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768f
            }
            return if (pcm.isEmpty()) null else GeneratedAudio(pcm, RATE)
        }
    }
}

/**
 * Text that came from the cloud brain. Only this text may be spoken by the cloud voice (D-035, D-053):
 * tool results, notes, notifications and screen text are never sent.
 */
object CloudSpeech {
    private const val KEEP = 40
    private val recent = ArrayDeque<String>()

    @Synchronized fun mark(text: String) {
        val t = SpeechText.normalize(text).lowercase()
        if (t.isBlank()) return
        recent.addLast(t)
        while (recent.size > KEEP) recent.removeFirst()
    }

    /** True when [chunk] is part of something the cloud wrote. */
    @Synchronized fun fromCloud(chunk: String): Boolean {
        val c = SpeechText.normalize(chunk).lowercase()
        return c.isNotBlank() && recent.any { c in it }
    }

    @Synchronized fun clear() = recent.clear()
}

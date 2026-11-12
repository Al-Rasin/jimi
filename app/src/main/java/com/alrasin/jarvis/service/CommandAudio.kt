package com.alrasin.jarvis.service

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.voice.Wav
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small helpers for command audio: loudness, joining chunks, saving to disk. */
internal object CommandAudio {
    /** RMS of a chunk mapped to 0..1 on a dB scale (-50 dB → 0, -10 dB → 1). */
    fun level(chunk: ShortArray): Float {
        var sum = 0.0
        for (s in chunk) sum += s.toDouble() * s
        val rms = kotlin.math.sqrt(sum / chunk.size.coerceAtLeast(1)) / 32768.0
        val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
        return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
    }

    /** Keeps each command's audio + raw STT text, to test better STT models on the user's real voice. */
    fun save(context: Context, audio: ShortArray, rawText: String): String? =
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "commands").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val wav = File(dir, "$name.wav")
            Wav.write(wav, audio)
            File(dir, "$name.txt").writeText(rawText + "\n")
            wav.path
        }.onFailure { JarvisLog.e("could not save command audio", it) }.getOrNull()

    fun join(parts: List<ShortArray>): ShortArray {
        val audio = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(audio, pos); pos += p.size }
        return audio
    }
}

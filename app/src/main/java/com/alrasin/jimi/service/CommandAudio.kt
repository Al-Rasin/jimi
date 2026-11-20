package com.alrasin.jimi.service

import android.content.Context
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.voice.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * Keeps each command's audio + raw STT text, to test better STT models on the user's real voice.
     * Returns the path at once; the file is written on [io] so the mic loop never waits for the disk.
     */
    fun save(context: Context, audio: ShortArray, rawText: String, io: CoroutineScope): String? =
        runCatching {
            val dir = File(context.getExternalFilesDir(null), "commands")
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val wav = File(dir, "$name.wav")
            io.launch(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    Wav.write(wav, audio)
                    File(dir, "$name.txt").writeText(rawText + "\n")
                }.onFailure { JimiLog.e("could not save command audio", it) }
            }
            wav.path
        }.onFailure { JimiLog.e("could not save command audio", it) }.getOrNull()

    fun join(parts: List<ShortArray>): ShortArray {
        val audio = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(audio, pos); pos += p.size }
        return audio
    }
}

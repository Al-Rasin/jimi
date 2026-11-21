package com.alrasin.jimi.service

import android.content.Context
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.voice.TextToSpeech
import com.alrasin.jimi.voice.Wav
import java.io.DataOutputStream
import java.io.File

/**
 * Debug only: saves what the mic hears while Jimi speaks, to tune barge-in "stop".
 * Off unless the file `<external files>/debug/bargein_dump` exists. Writes `debug/bargein_<time>.wav` (mic),
 * `.aec.wav` (mic after the echo canceler), `.far.wav` (its playback reference), `.txt` (per 80 ms chunk: playback RMS and playback head frame) and `.ref` (streamed playback pieces:
 * int64 start frame, int32 rate, int32 count, float32 samples).
 */
class BargeInDump(context: Context) {
    private val dir = context.getExternalFilesDir(null)?.let { File(it, "debug") }
    private val chunks = ArrayList<ShortArray>()
    private val cleaned = ArrayList<ShortArray>()
    private val far = ArrayList<ShortArray>()
    private val info = StringBuilder()
    private val pieces = ArrayList<Triple<Long, FloatArray, Int>>()
    private var on = false

    init {
        if (dir != null && File(dir, FLAG).exists()) {
            TextToSpeech.debugTap = { start, pcm, rate -> synchronized(pieces) { pieces.add(Triple(start, pcm.copyOf(), rate)) } }
        }
    }

    fun add(chunk: ShortArray, playbackEnergy: Float, echoCanceled: ShortArray? = null, reference: ShortArray? = null) {
        if (chunks.isEmpty()) on = dir != null && File(dir, FLAG).exists()
        if (!on || chunks.size >= MAX_CHUNKS) return
        chunks.add(chunk.copyOf())
        echoCanceled?.let { cleaned.add(it.copyOf()) }
        reference?.let { far.add(it.copyOf()) }
        info.append(playbackEnergy).append(' ').append(TextToSpeech.playbackHead).append('\n')
    }

    fun flush() {
        if (chunks.isEmpty()) return
        if (on && dir != null) {
            val name = "bargein_${System.currentTimeMillis()}"
            runCatching {
                Wav.write(File(dir, "$name.wav"), CommandAudio.join(chunks))
                if (far.isNotEmpty()) Wav.write(File(dir, "$name.far.wav"), CommandAudio.join(far))
                if (cleaned.isNotEmpty()) Wav.write(File(dir, "$name.aec.wav"), CommandAudio.join(cleaned))
                File(dir, "$name.txt").writeText(info.toString())
                DataOutputStream(File(dir, "$name.ref").outputStream().buffered()).use { out ->
                    synchronized(pieces) {
                        for ((start, pcm, rate) in pieces) {
                            out.writeLong(start); out.writeInt(rate); out.writeInt(pcm.size)
                            pcm.forEach(out::writeFloat)
                        }
                    }
                }
            }.onSuccess { JimiLog.i("barge-in dump $name (${chunks.size} chunks)") }
        }
        chunks.clear()
        cleaned.clear()
        far.clear()
        info.setLength(0)
        synchronized(pieces) { pieces.clear() }
        on = false
    }

    private companion object {
        const val FLAG = "bargein_dump"
        const val MAX_CHUNKS = 750   // 60 s
    }
}

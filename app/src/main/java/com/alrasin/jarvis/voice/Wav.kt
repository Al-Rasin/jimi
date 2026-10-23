package com.alrasin.jarvis.voice

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal 16-bit mono PCM WAV writer. */
object Wav {
    fun bytes(samples: ShortArray, sampleRate: Int = 16_000): ByteArray {
        val dataLen = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        samples.forEach { b.putShort(it) }
        return b.array()
    }

    fun write(file: File, samples: ShortArray, sampleRate: Int = 16_000) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes(samples, sampleRate))
    }

    /** Sample rate from the "fmt " chunk (16 kHz if there is none). */
    fun sampleRate(file: File): Int {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(p + it) })
            val len = b.getInt(p + 4)
            if (id == "fmt " && p + 16 <= b.limit()) return b.getInt(p + 12)
            p += 8 + len + (len and 1)
        }
        return 16_000
    }

    /** Reads a 16-bit mono WAV written by [write] (or ffmpeg -ac 1 -ar 16000). */
    fun read(file: File): ShortArray {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        var p = 12
        while (p + 8 <= b.limit()) {
            val id = String(ByteArray(4) { b.get(p + it) })
            val len = b.getInt(p + 4)
            if (id == "data") {
                val n = minOf(len, b.limit() - p - 8) / 2
                return ShortArray(n) { b.getShort(p + 8 + it * 2) }
            }
            p += 8 + len + (len and 1)
        }
        return ShortArray(0)
    }
}

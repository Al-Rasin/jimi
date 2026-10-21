package com.alrasin.jarvis.voice

/** Fixed-size ring buffer of 16-bit samples. Keeps the last [capacity] samples. */
class AudioRing(private val capacity: Int) {
    private val data = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    fun write(samples: ShortArray, count: Int = samples.size) {
        for (i in 0 until count) {
            data[pos] = samples[i]
            pos = (pos + 1) % capacity
        }
        filled = minOf(filled + count, capacity)
    }

    /** Last [n] samples, oldest first (fewer if not enough were written yet). */
    fun last(n: Int): ShortArray {
        val len = minOf(n, filled)
        val out = ShortArray(len)
        val start = (pos - len + capacity) % capacity
        for (i in 0 until len) out[i] = data[(start + i) % capacity]
        return out
    }
}

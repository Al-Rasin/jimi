package com.alrasin.jarvis.voice

/** Fixed-size ring buffer of 16-bit samples. Keeps the last [capacity] samples. */
class AudioRing(private val capacity: Int) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val data = ShortArray(capacity)
    private var pos = 0
    private var filled = 0

    fun write(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) { "count must fit samples" }
        if (count >= capacity) {
            samples.copyInto(data, startIndex = count - capacity, endIndex = count)
            pos = 0
            filled = capacity
            return
        }
        val first = minOf(count, capacity - pos)
        samples.copyInto(data, destinationOffset = pos, endIndex = first)
        samples.copyInto(data, startIndex = first, endIndex = count)
        pos = if (first == capacity - pos) count - first else pos + first
        filled += minOf(count, capacity - filled)
    }

    /** Last [n] samples, oldest first (fewer if not enough were written yet). */
    fun last(n: Int): ShortArray {
        require(n >= 0) { "sample count must not be negative" }
        val len = minOf(n, filled)
        val out = ShortArray(len)
        val start = if (pos >= len) pos - len else capacity - (len - pos)
        val first = minOf(len, capacity - start)
        data.copyInto(out, startIndex = start, endIndex = start + first)
        data.copyInto(out, destinationOffset = first, endIndex = len - first)
        return out
    }
}

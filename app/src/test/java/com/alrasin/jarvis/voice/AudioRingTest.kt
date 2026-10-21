package com.alrasin.jarvis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class AudioRingTest {
    @Test
    fun keepsLastSamplesInOrder() {
        val ring = AudioRing(4)
        ring.write(shortArrayOf(1, 2, 3))
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.last(10))
        ring.write(shortArrayOf(4, 5, 6))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), ring.last(4))
        assertArrayEquals(shortArrayOf(5, 6), ring.last(2))
    }
}

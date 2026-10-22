package com.alrasin.jarvis.voice

import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioRingCaptureTest {
    @Test
    fun startupReturnsOnlyRecordedSamplesWithoutLeadingZeros() {
        val ring = AudioRing(8)
        assertArrayEquals(shortArrayOf(), ring.last(8))
        ring.write(shortArrayOf(10, 20, 30, 99), 3)
        assertArrayEquals(shortArrayOf(10, 20, 30), ring.last(8))
        assertArrayEquals(shortArrayOf(), ring.last(0))
    }

    @Test
    fun wakeSnapshotIncludesTriggeringChunkAndOverlappingCommand() {
        val ring = AudioRing(6)
        ring.write(shortArrayOf(1, 2, 3, 4))
        ring.write(shortArrayOf(5, 6, 7, 8))
        val preRoll = ring.last(6)
        ring.write(shortArrayOf(9, 10))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6, 7, 8), preRoll)
        assertArrayEquals(shortArrayOf(5, 6, 7, 8, 9, 10), ring.last(6))
    }

    @Test
    fun oversizedWriteKeepsOnlyTailOfRequestedCount() {
        val ring = AudioRing(3)
        ring.write(shortArrayOf(20, 21))
        ring.write(shortArrayOf(1, 2, 3, 4, 5, 6, 99), 6)
        assertArrayEquals(shortArrayOf(4, 5, 6), ring.last(Int.MAX_VALUE))
        ring.write(shortArrayOf(7))
        assertArrayEquals(shortArrayOf(5, 6, 7), ring.last(3))
    }

    @Test
    fun snapshotsAndInputHaveIndependentOwnership() {
        val ring = AudioRing(3)
        val input = shortArrayOf(1, 2, 3)
        ring.write(input)
        input.fill(9)
        val snapshot = ring.last(3)
        assertArrayEquals(shortArrayOf(1, 2, 3), snapshot)
        snapshot.fill(8)
        assertArrayEquals(shortArrayOf(1, 2, 3), ring.last(3))
    }

    @Test
    fun zeroWriteLeavesPartiallyFullAndWrappedBuffersUntouched() {
        val ring = AudioRing(3)
        ring.write(shortArrayOf(1))
        ring.write(shortArrayOf())
        assertArrayEquals(shortArrayOf(1), ring.last(3))
        ring.write(shortArrayOf(2, 3, 4, 5))
        ring.write(shortArrayOf(99), 0)
        assertArrayEquals(shortArrayOf(3, 4, 5), ring.last(3))
    }

    @Test
    fun invalidCountsFailWithoutChangingBufferedAudio() {
        val ring = AudioRing(3)
        ring.write(shortArrayOf(1, 2))
        assertThrows(IllegalArgumentException::class.java) { ring.write(shortArrayOf(8), 2) }
        assertThrows(IllegalArgumentException::class.java) { ring.write(shortArrayOf(8), -1) }
        assertThrows(IllegalArgumentException::class.java) { ring.last(-1) }
        assertArrayEquals(shortArrayOf(1, 2), ring.last(3))
    }

    @Test
    fun capacityMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { AudioRing(0) }
        assertThrows(IllegalArgumentException::class.java) { AudioRing(-1) }
    }

    @Test
    fun arbitraryChunkBoundariesMatchChronologicalReference() {
        val random = Random(42)
        for (capacity in listOf(1, 2, 7, 160)) {
            val ring = AudioRing(capacity)
            val history = ArrayList<Short>()
            repeat(100) {
                val samples = ShortArray(random.nextInt(capacity * 3 + 1)) { random.nextInt().toShort() }
                val count = random.nextInt(samples.size + 1)
                ring.write(samples, count)
                history.addAll(samples.take(count))
                for (requested in listOf(0, 1, capacity / 2, capacity, capacity + 1)) {
                    assertArrayEquals(history.takeLast(minOf(requested, capacity)).toShortArray(), ring.last(requested))
                }
            }
        }
    }
}

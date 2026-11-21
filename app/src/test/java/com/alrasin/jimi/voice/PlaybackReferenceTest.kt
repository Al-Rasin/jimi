package com.alrasin.jimi.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class PlaybackReferenceTest {
    private class FakeSource(val rate: Int, val signal: (Long) -> Float) : PlaybackReference.Source {
        var head = 0L
        var id = 1
        val copies = ArrayList<Long>()
        override fun clock() = PlaybackReference.Clock(id, rate, head)
        override fun copy(id: Int, from: Long, out: FloatArray): Boolean {
            if (id != this.id) return false
            copies.add(from)
            for (i in out.indices) out[i] = if (from + i >= 0) signal(from + i) else 0f
            return true
        }
    }

    private fun rms(a: ShortArray) = sqrt(a.sumOf { it.toDouble() * it } / a.size) / 32767

    @Test fun sameRateCopiesTheChunkBeforeTheHead() {
        val tone = { n: Long -> 0.5f * sin(2 * PI * 500 * n / 16_000).toFloat() }
        val src = FakeSource(16_000, tone)
        src.head = 16_000
        val ref = PlaybackReference(src).next()
        for (i in 0 until 1280) assertEquals(tone(14_720L + i) * 32767, ref[i].toFloat(), 120f)
    }

    @Test fun downsamplesWithoutLosingLevel() {
        val src = FakeSource(48_000) { 0.5f * sin(2 * PI * 1000 * it / 48_000).toFloat() }
        src.head = 48_000
        val ref = PlaybackReference(src).next()
        assertEquals(0.5 / sqrt(2.0), rms(ref), 0.02)
    }

    @Test fun removesToneAboveNyquist() {
        val src = FakeSource(48_000) { 0.5f * sin(2 * PI * 12_000 * it / 48_000).toFloat() }
        src.head = 48_000
        assertTrue(rms(PlaybackReference(src).next()) < 0.01)
    }

    @Test fun advancesOneChunkDespiteHeadJitter() {
        val src = FakeSource(16_000) { 0f }
        val ref = PlaybackReference(src)
        src.head = 16_000; ref.next()
        src.head = 16_000 + 1280 + 900; ref.next()
        src.head = 16_000 + 2560 - 900; ref.next()
        val w = src.copies[0] - (16_000 - 1280)
        assertEquals(listOf(14_720L, 16_000L, 17_280L).map { it + w }, src.copies)
    }

    @Test fun relocksOnBigDriftOrNewStream() {
        val src = FakeSource(16_000) { 0f }
        val ref = PlaybackReference(src)
        src.head = 16_000; ref.next()
        src.head = 16_000 + 1280 + 3000; ref.next()
        src.id = 2; src.head = 5_000; ref.next()
        val w = src.copies[0] - (16_000 - 1280)
        assertEquals(listOf(14_720L, 19_000L, 3_720L).map { it + w }, src.copies)
    }

    @Test fun silentWithoutPlayback() {
        val ref = PlaybackReference(object : PlaybackReference.Source {
            override fun clock(): PlaybackReference.Clock? = null
            override fun copy(id: Int, from: Long, out: FloatArray) = false
        })
        assertArrayEquals(ShortArray(1280), ref.next())
    }
}

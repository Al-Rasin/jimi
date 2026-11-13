package com.alrasin.jarvis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseCleanTest {
    @Test fun usedOnlyWhenOnAndModelReady() {
        assertTrue(NoiseClean.use(enabled = true, ready = true))
        assertFalse(NoiseClean.use(enabled = true, ready = false))
        assertFalse(NoiseClean.use(enabled = false, ready = true))
    }

    @Test fun floatsUseOnlyCount() {
        val f = NoiseClean.toFloats(shortArrayOf(16384, -32768, 5), count = 2)
        assertArrayEquals(floatArrayOf(0.5f, -1f), f, 0f)
    }

    @Test fun shortsClipLoudOutput() {
        val s = NoiseClean.toShorts(floatArrayOf(0.5f, 1.5f, -2f, 0f))
        assertArrayEquals(shortArrayOf(16384, Short.MAX_VALUE, Short.MIN_VALUE, 0), s)
    }

    @Test fun roundTripKeepsSamples() {
        val raw = shortArrayOf(0, 1, -1, 1234, -32768, 32767)
        assertArrayEquals(raw, NoiseClean.toShorts(NoiseClean.toFloats(raw)))
    }

    @Test fun emptyOutputIsEmpty() {
        assertEquals(0, NoiseClean.toShorts(FloatArray(0)).size)
    }
}

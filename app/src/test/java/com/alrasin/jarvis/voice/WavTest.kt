package com.alrasin.jarvis.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class WavTest {
    @Test
    fun writeThenReadRoundTrip() {
        val samples = ShortArray(1000) { (it * 31 - 15000).toShort() }
        val f = File.createTempFile("wav", ".wav")
        try {
            Wav.write(f, samples)
            assertEquals(44L + 2000, f.length())
            assertArrayEquals(samples, Wav.read(f))
        } finally {
            f.delete()
        }
    }
}

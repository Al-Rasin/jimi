package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerMathTest {
    @Test fun cosineOfSameIsOne() = assertEquals(1f, SpeakerVerifier.cosine(floatArrayOf(1f, 2f, 3f), floatArrayOf(2f, 4f, 6f)), 1e-5f)

    @Test fun cosineOfOrthogonalIsZero() = assertEquals(0f, SpeakerVerifier.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 3f)), 1e-5f)

    @Test fun meanIsNormalized() {
        val m = SpeakerVerifier.mean(listOf(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)))
        assertEquals(1f, m[0] * m[0] + m[1] * m[1], 1e-5f)
        assertTrue(SpeakerVerifier.cosine(m, floatArrayOf(1f, 1f)) > 0.999f)
    }
}

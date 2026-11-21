package com.alrasin.jimi.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WakeVerifierTest {
    private val n = WakeVerifier.SIZE

    private fun bin(mean: Float, scale: Float, coef: Float, bias: Float): ByteArray {
        val b = ByteBuffer.allocate((3 * n + 1) * 4).order(ByteOrder.LITTLE_ENDIAN)
        repeat(n) { b.putFloat(mean) }
        repeat(n) { b.putFloat(scale) }
        repeat(n) { b.putFloat(coef) }
        b.putFloat(bias)
        return b.array()
    }

    @Test fun scoreIsSigmoidOfScaledDotProduct() {
        val v = WakeVerifier.parse(bin(mean = 1f, scale = 2f, coef = 0.001f, bias = -0.5f))!!
        // each term (3-1)/2*0.001 = 0.001; sum = n*0.001 = 1.536; z = 1.036
        val expected = 1.0 / (1.0 + Math.exp(-(n * 0.001 - 0.5)))
        assertEquals(expected.toFloat(), v.score(FloatArray(n) { 3f }), 1e-4f)
        assertEquals(0.5f, WakeVerifier.parse(bin(0f, 1f, 0f, 0f))!!.score(FloatArray(n) { 7f }), 1e-6f)
    }

    @Test fun badFilesAreRejected() {
        assertNull(WakeVerifier.parse(ByteArray(100)))
        assertNull(WakeVerifier.parse(bin(0f, 0f, 1f, 0f)))   // zero scale would divide by zero
        assertNotNull(WakeVerifier.parse(bin(0f, 1f, 1f, 0f)))
    }
}

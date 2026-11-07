package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmShapeTest {
    private val rate = 1000   // 1 sample = 1 ms, easy to read

    @Test fun cutsEdgeSilenceAndKeepsALittleAir() {
        val pcm = FloatArray(1000).also { for (i in 400 until 600) it[i] = 0.5f }
        val out = PcmShape.shape(pcm, rate)
        assertEquals(40 + 200 + 60, out.size)
        assertEquals(0.5f, out[150], 0f)
    }

    @Test fun addsPauseAsSilence() {
        val pcm = FloatArray(300) { 0.5f }
        val out = PcmShape.shape(pcm, rate, pauseMs = 280)
        assertEquals(300 + 280, out.size)
        assertTrue(out.drop(300).all { it == 0f })
    }

    @Test fun fadesEdgesSoNoClick() {
        val out = PcmShape.shape(FloatArray(300) { 0.5f }, rate)
        assertEquals(0f, out.first(), 0f)
        assertEquals(0f, out[out.size - 1], 0f)
        assertEquals(0.5f, out[150], 0f)
    }

    @Test fun allSilenceGivesNothing() {
        assertEquals(0, PcmShape.shape(FloatArray(500) { 0.001f }, rate, pauseMs = 300).size)
        assertEquals(0, PcmShape.shape(FloatArray(0), rate).size)
    }
}

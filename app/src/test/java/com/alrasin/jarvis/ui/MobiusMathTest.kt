package com.alrasin.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.hypot

class MobiusMathTest {
    @Test fun middleLineIsTheCircle() {
        for (u in listOf(0f, 1f, 2.5f, 4f)) {
            val p = MobiusMath.point(u, 0f, 0.3f)
            assertEquals(1f, hypot(p[0], p[1]), 1e-4f)
            assertEquals(0f, p[2], 1e-6f)
        }
    }

    @Test fun oneTurnFlipsTheSide() {
        val start = MobiusMath.normal(0f, 0.3f)
        val end = MobiusMath.normal((2 * PI).toFloat(), 0.3f)
        for (i in 0..2) assertEquals(-start[i], end[i], 1e-2f)
    }

    @Test fun facesSortedFarToNearAndPartialLoop() {
        val all = MobiusMath.faces(MobiusMath.Pose(spin = 0.7f), segments = 60)
        assertEquals(60, all.size)
        assertTrue(all.zipWithNext().all { (a, b) -> a.depth <= b.depth })
        assertTrue(all.all { it.light in 0f..1f })
        assertEquals(30, MobiusMath.faces(MobiusMath.Pose(), segments = 60, shown = 0.5f).size)
        assertTrue(MobiusMath.faces(MobiusMath.Pose(), shown = 0f).isEmpty())
    }

    @Test fun wavesBendTheRadius() {
        val pose = MobiusMath.Pose(waves = listOf(Triple(0.1f, 2, 0f)))
        assertEquals(1.1f, MobiusMath.radius((PI / 4).toFloat(), pose), 1e-4f)
        assertEquals(1f, MobiusMath.radius(0f, MobiusMath.Pose()), 0f)
    }

    @Test fun blendWrapsRound() {
        val colors = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertEquals(0xFF000000.toInt(), MobiusMath.blend(colors, 0f))
        assertEquals(0xFF000000.toInt(), MobiusMath.blend(colors, 1f))
        assertEquals(0xFF7F7F7F.toInt(), MobiusMath.blend(colors, 0.25f))
    }

    @Test fun boxBlurKeepsFlatColorAndSpreadsADot() {
        val flat = IntArray(25) { 0xFF336699.toInt() }
        BoxBlur.blur(flat, 5, 5, 1)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        val dot = IntArray(25) { 0xFF000000.toInt() }
        dot[12] = 0xFFFFFFFF.toInt()
        BoxBlur.blur(dot, 5, 5, 1)
        assertTrue(dot[12] and 0xFF in 1..254)
        assertTrue(dot[6] and 0xFF > 0)
        assertEquals(0, dot[0] and 0xFF)
    }

    @Test fun tabAtPositionIsFrontAndMiddle() {
        val n = 6
        for (pos in 0 until n) {
            val pose = MobiusMath.Pose(spin = MobiusMath.tabSpin(pos.toFloat(), n), tilt = 1.25f)
            val at = (0 until n).map { MobiusMath.project(MobiusMath.point(MobiusMath.tabU(it, n), 0f, 0.3f), pose) }
            assertEquals(0f, at[pos][0], 1e-4f)
            assertTrue(at[pos][1] > 0f)
            assertEquals(pos, at.indices.maxBy { at[it][2] })
            // the next tab is to the right
            assertTrue(at[(pos + 1) % n][0] > 0f)
        }
    }
}

class ShortWayTest {
    @Test fun goesTheShortWayRound() {
        assertEquals(1, shortWay(0, 1, 6))
        assertEquals(-1, shortWay(0, 5, 6))
        assertEquals(1, shortWay(5, 0, 6))
        assertEquals(3, shortWay(0, 3, 6))
        assertEquals(0, shortWay(2, 2, 6))
    }
}

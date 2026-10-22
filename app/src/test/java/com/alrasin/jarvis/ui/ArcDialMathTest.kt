package com.alrasin.jarvis.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArcDialMathTest {
    @Test fun pointerTracksTopAndSides() {
        assertEquals(0f, ArcDialMath.pointerAngle(100f, 0f, 100f, 100f), 0.001f)
        assertEquals(90f, ArcDialMath.pointerAngle(200f, 100f, 100f, 100f), 0.001f)
        assertEquals(-90f, ArcDialMath.pointerAngle(0f, 100f, 100f, 100f), 0.001f)
    }

    @Test fun crossingAngleSeamTakesShortPath() {
        assertEquals(2f, ArcDialMath.delta(179f, -179f), 0.001f)
        assertEquals(-2f, ArcDialMath.delta(-179f, 179f), 0.001f)
    }

    @Test fun snapUsesNearestItemAndProjectedFling() {
        assertEquals(0, ArcDialMath.snapIndex(17f, 0f, 6))
        assertEquals(1, ArcDialMath.snapIndex(19f, 0f, 6))
        assertEquals(2, ArcDialMath.snapIndex(36f, 240f, 6))
        assertEquals(0, ArcDialMath.snapIndex(36f, -240f, 6))
        assertEquals(1, ArcDialMath.snapIndex(44f, 0f, 4))
    }

    @Test fun endsNeverWrapAndSingleItemStaysSelected() {
        assertEquals(0, ArcDialMath.snapIndex(-200f, -1000f, 6))
        assertEquals(5, ArcDialMath.snapIndex(500f, 1000f, 6))
        assertEquals(0, ArcDialMath.snapIndex(500f, 1000f, 1))
    }

    @Test fun rubberBandPreservesRangeAndLimitsOvershoot() {
        assertEquals(72f, ArcDialMath.rubberBand(72f, 6), 0.001f)
        assertTrue(ArcDialMath.rubberBand(-500f, 6) in -12f..0f)
        assertTrue(ArcDialMath.rubberBand(500f, 6) in 180f..192f)
        assertEquals(-6f, ArcDialMath.rubberBand(-12f, 6), 0.001f)
    }
}

package com.alrasin.jarvis.ui

import kotlin.math.atan2
import kotlin.math.roundToInt

object ArcDialMath {
    fun step(itemCount: Int): Float = if (itemCount == 4) 45f else 36f

    fun pointerAngle(x: Float, y: Float, centerX: Float, centerY: Float): Float =
        Math.toDegrees(atan2((x - centerX).toDouble(), (centerY - y).toDouble())).toFloat()

    fun delta(previous: Float, current: Float): Float =
        ((current - previous + 540f) % 360f) - 180f

    fun snapIndex(angle: Float, velocity: Float, itemCount: Int): Int {
        require(itemCount > 0)
        return ((angle + velocity * 0.15f) / step(itemCount)).roundToInt().coerceIn(0, itemCount - 1)
    }

    fun rubberBand(angle: Float, itemCount: Int): Float {
        require(itemCount > 0)
        val end = (itemCount - 1) * step(itemCount)
        val bound = angle.coerceIn(0f, end)
        val excess = angle - bound
        return bound + 12f * excess / (12f + kotlin.math.abs(excess))
    }
}

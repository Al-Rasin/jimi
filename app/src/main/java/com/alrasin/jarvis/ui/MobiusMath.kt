package com.alrasin.jarvis.ui

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometry of Jimi's Möbius strip (D-050): a band with one half twist, turned in 3D and projected to 2D.
 * Pure Kotlin so it is unit tested; the composable only paints the faces this returns.
 */
object MobiusMath {
    private const val TWO_PI = (2 * PI).toFloat()

    /** One small piece of the band, already projected. Points go round: inner-left, outer-left, outer-right, inner-right. */
    class Face(val xs: FloatArray, val ys: FloatArray, val depth: Float, val light: Float, val along: Float)

    /** How the band moves; all angles in radians. [waves] bend the radius, each (amplitude, count, phase). */
    data class Pose(
        val spin: Float = 0f,
        val tilt: Float = 0.9f,
        val sway: Float = 0f,
        val halfWidth: Float = 0.3f,
        val waves: List<Triple<Float, Int, Float>> = emptyList(),
    )

    /** Point on the band: u goes round (0..2π), v goes across (-1..1). */
    fun point(u: Float, v: Float, halfWidth: Float, radius: Float = 1f): FloatArray {
        val r = radius + v * halfWidth * cos(u / 2)
        return floatArrayOf(r * cos(u), r * sin(u), v * halfWidth * sin(u / 2))
    }

    /** Surface normal at (u, 0). After one full turn it points the other way: the band has one side. */
    fun normal(u: Float, halfWidth: Float): FloatArray {
        val a = point(u, 0f, halfWidth)
        val b = point(u + 1e-3f, 0f, halfWidth)
        val c = point(u, 1f, halfWidth)
        return unit(cross(sub(b, a), sub(c, a)))
    }

    fun radius(u: Float, pose: Pose): Float = 1f + pose.waves.fold(0f) { s, (amp, n, ph) -> s + amp * sin(n * u - ph) }

    /**
     * Faces sorted far to near (paint in this order). [shown] 0..1 draws only part of the loop (opening animation).
     * Coordinates are in -1..1 (times [scale]); the caller maps them to pixels.
     */
    fun faces(pose: Pose, segments: Int = 144, shown: Float = 1f, scale: Float = 1f): List<Face> {
        val count = (segments * shown.coerceIn(0f, 1f)).toInt()
        if (count == 0) return emptyList()
        val cs = cos(pose.spin); val ss = sin(pose.spin)
        val ct = cos(pose.tilt); val st = sin(pose.tilt)
        val cw = cos(pose.sway); val sw = sin(pose.sway)
        fun project(p: FloatArray): FloatArray {
            // spin round z, tilt round x, sway round y, then a light perspective
            val x1 = p[0] * cs - p[1] * ss; val y1 = p[0] * ss + p[1] * cs; val z1 = p[2]
            val y2 = y1 * ct - z1 * st; val z2 = y1 * st + z1 * ct
            val x3 = x1 * cw + z2 * sw; val z3 = -x1 * sw + z2 * cw
            val k = 3.2f / (3.2f - z3)
            return floatArrayOf(x3 * k * scale, y2 * k * scale, z3)
        }
        val light = floatArrayOf(-0.35f, -0.55f, 0.76f)
        val ring = Array(count + 1) { i ->
            val u = TWO_PI * i / segments
            val r = radius(u, pose)
            arrayOf(point(u, -1f, pose.halfWidth, r), point(u, 1f, pose.halfWidth, r))
        }
        val out = ArrayList<Face>(count)
        for (i in 0 until count) {
            val a = project(ring[i][0]); val b = project(ring[i][1])
            val c = project(ring[i + 1][1]); val d = project(ring[i + 1][0])
            val n = unit(cross(sub(c, a), sub(b, d)))
            // the side turned away from the viewer is darker, so the twist shows
            val back = (c[0] - a[0]) * (b[1] - d[1]) - (c[1] - a[1]) * (b[0] - d[0]) < 0
            val lit = (0.4f + 0.6f * abs(n[0] * light[0] + n[1] * light[1] + n[2] * light[2])) * (if (back) 0.7f else 1f)
            out += Face(
                floatArrayOf(a[0], b[0], c[0], d[0]), floatArrayOf(a[1], b[1], c[1], d[1]),
                (a[2] + b[2] + c[2] + d[2]) / 4, lit, (i + 0.5f) / segments,
            )
        }
        out.sortBy { it.depth }
        return out
    }

    /** Linear blend of colors given as ARGB ints, [t] wraps round (0..1). */
    fun blend(colors: IntArray, t: Float): Int {
        val f = ((t % 1f) + 1f) % 1f * colors.size
        val i = f.toInt() % colors.size
        val j = (i + 1) % colors.size
        val k = f - f.toInt()
        fun ch(c: Int, s: Int) = (c shr s) and 0xFF
        fun mix(s: Int) = (ch(colors[i], s) + (ch(colors[j], s) - ch(colors[i], s)) * k).toInt().coerceIn(0, 255)
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun sub(a: FloatArray, b: FloatArray) = floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun cross(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun unit(a: FloatArray): FloatArray {
        val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).takeIf { it > 1e-9f } ?: return floatArrayOf(0f, 0f, 1f)
        return floatArrayOf(a[0] / l, a[1] / l, a[2] / l)
    }
}

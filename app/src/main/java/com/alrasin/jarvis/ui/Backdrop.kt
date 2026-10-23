package com.alrasin.jarvis.ui

import android.graphics.Bitmap
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blurred picture of the screen behind the panel (D-050). Android 11 has no window blur, so the panel takes a
 * screenshot through the accessibility service just before it opens, shrinks and blurs it, and shows it behind
 * the glass. Kept in memory only, dropped when the panel closes.
 */
object Backdrop {
    private const val WIDTH = 180
    private const val RADIUS = 3

    private val _image = MutableStateFlow<Bitmap?>(null)
    val image: StateFlow<Bitmap?> = _image

    /** Takes the picture; gives up after [timeoutMs] so the panel is never late. */
    suspend fun capture(timeoutMs: Long = 350) {
        val service = JarvisAccessibilityService.instance ?: return
        val t0 = System.currentTimeMillis()
        val shot = withTimeoutOrNull(timeoutMs) { service.screenshot() } ?: return
        _image.value = withContext(Dispatchers.Default) {
            val h = (WIDTH * shot.height / shot.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(shot, WIDTH, h, true)
            shot.recycle()
            val px = IntArray(WIDTH * h)
            small.getPixels(px, 0, WIDTH, 0, 0, WIDTH, h)
            repeat(3) { BoxBlur.blur(px, WIDTH, h, RADIUS) }
            Bitmap.createBitmap(px, WIDTH, h, Bitmap.Config.ARGB_8888).also { small.recycle() }
        }
        JarvisLog.i("panel backdrop in ${System.currentTimeMillis() - t0} ms")
    }

    fun clear() { _image.value = null }
}

/** Two-pass box blur on ARGB pixels, in place. Three passes look like a Gaussian blur. Pure Kotlin: unit tested. */
object BoxBlur {
    fun blur(px: IntArray, w: Int, h: Int, r: Int) {
        if (r <= 0 || w <= 0 || h <= 0) return
        val tmp = IntArray(px.size)
        pass(px, tmp, w, h, r, horizontal = true)
        pass(tmp, px, w, h, r, horizontal = false)
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            fun at(i: Int) = src[if (horizontal) line * w + i.coerceIn(0, len - 1) else i.coerceIn(0, len - 1) * w + line]
            var a = 0; var red = 0; var g = 0; var b = 0
            for (i in -r..r) { val c = at(i); a += c ushr 24; red += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF }
            val n = 2 * r + 1
            for (i in 0 until len) {
                dst[if (horizontal) line * w + i else i * w + line] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
                val add = at(i + r + 1); val drop = at(i - r)
                a += (add ushr 24) - (drop ushr 24)
                red += ((add shr 16) and 0xFF) - ((drop shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((drop shr 8) and 0xFF)
                b += (add and 0xFF) - (drop and 0xFF)
            }
        }
    }
}

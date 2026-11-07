package com.alrasin.jarvis.agent

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.core.JarvisLog
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/**
 * OCR for screens with no text in the accessibility tree (ROADMAP §H.8): Flutter apps, games, WebViews, images.
 * Screenshot → ML Kit Text Recognition v2 (bundled Latin model, offline) → words + boxes as [Skills.Item]s
 * with no node, so the normal pickers work and [UiQuery.tapAt] taps the box centre.
 * The pure helpers are unit tested; [read] needs a live accessibility service.
 */
object ScreenOcr {
    /** One OCR line or word with its box in screen pixels. */
    data class Box(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int, val word: Boolean = false)

    /** Fewer labelled items than this in the tree = "the tree is empty", so OCR before scrolling. */
    private const val THIN_TREE = 3

    /** Lines plus their single words, so "Settings" is found inside a one-line tab bar "Home Settings Profile". */
    fun items(boxes: List<Box>): List<Skills.Item> = boxes
        .filter { it.text.isNotBlank() && it.right > it.left && it.bottom > it.top }
        .distinctBy { listOf(it.text.lowercase(), it.left / 8, it.top / 8) }
        .map { Skills.Item(text = it.text.trim(), left = it.left, top = it.top, right = it.right, bottom = it.bottom) }

    /** Screen text in reading order (top to bottom, then left to right), lines only. */
    fun text(boxes: List<Box>): String = boxes.filter { !it.word && it.text.isNotBlank() }
        .sortedWith(compareBy<Box> { (it.top + it.bottom) / 2 / ROW_PX }.thenBy { it.left })
        .joinToString("\n") { it.text.trim() }

    /** True when the screen tree has almost no text, so OCR is worth trying. */
    fun treeIsThin(items: List<Skills.Item>): Boolean = items.count { it.label.isNotBlank() } < THIN_TREE

    /** Centre of an OCR item, to tap. */
    fun centre(item: Skills.Item): Pair<Int, Int> = (item.left + item.right) / 2 to (item.top + item.bottom) / 2

    private const val ROW_PX = 24

    // ------------------------------------------------------------------ Android side

    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

    /** OCR of the screen now, or null when no screenshot could be taken. */
    suspend fun read(service: JarvisAccessibilityService): List<Box>? {
        val t0 = SystemClock.elapsedRealtime()
        val bmp = shot(service) ?: return null
        val t1 = SystemClock.elapsedRealtime()
        val boxes = try {
            recognize(bmp)
        } finally {
            bmp.recycle()
        }
        JarvisLog.i("ocr: ${boxes?.count { !it.word } ?: -1} lines, shot ${t1 - t0} ms, ocr ${SystemClock.elapsedRealtime() - t1} ms")
        return boxes
    }

    /** Accessibility screenshot; it is rate-limited (≈1/s), so retry once, then root `screencap`. */
    private suspend fun shot(service: JarvisAccessibilityService): Bitmap? {
        service.screenshot()?.let { return it }
        delay(1_100)
        service.screenshot()?.let { return it }
        val f = File(service.cacheDir, "ocr.png")
        val q = RootShell.quote(f.path)
        val r = RootShell.run("screencap -p $q && chmod 644 $q", log = false)
        val bmp = if (r.ok) BitmapFactory.decodeFile(f.path) else null
        f.delete()
        return bmp
    }

    /** Find [name] by OCR: Found item has no node, tap it with [UiQuery.tapAt] at [centre]. */
    suspend fun find(service: JarvisAccessibilityService, name: String): Skills.Pick {
        val boxes = read(service) ?: return Skills.Pick.NotFound
        return Skills.pickNamed(items(boxes), name)
    }

    /** OCR text of the screen, or "" when nothing found. */
    suspend fun screenText(service: JarvisAccessibilityService): String = read(service)?.let { text(it) }.orEmpty()

    private suspend fun recognize(bmp: Bitmap): List<Box>? = suspendCancellableCoroutine { cont ->
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(boxes(it)) }
            .addOnFailureListener {
                JarvisLog.i("ocr failed: ${it.message}")
                if (cont.isActive) cont.resume(null)
            }
    }

    private fun boxes(t: Text): List<Box> = buildList {
        for (block in t.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            add(Box(line.text, r.left, r.top, r.right, r.bottom))
            if (line.elements.size > 1) for (e in line.elements) {
                val w = e.boundingBox ?: continue
                add(Box(e.text, w.left, w.top, w.right, w.bottom, word = true))
            }
        }
    }
}

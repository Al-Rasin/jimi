package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import com.alrasin.jarvis.agent.ScreenOcr
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.service.JarvisCoreService
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Camera + QR (ROADMAP #32, §I.7). Photo / selfie / video: open the phone's camera app, count down out loud,
 * press the shutter (accessibility tap, then camera / volume key by root), and check DCIM for a new file.
 * QR: scan screenshots (of the camera preview or of the screen) with ML Kit's bundled barcode model, offline.
 * Links are never opened by the scan; the user says "open the link" (a confirmed step).
 */
object CameraText {
    const val MAX_DELAY_S = 10
    const val DEFAULT_PHOTO_DELAY_S = 3

    /** Shutter button labels / ids seen in MIUI Camera, Google Camera and AOSP camera apps. */
    val SHUTTER_LABELS = listOf("Shutter", "Shutter button", "Take photo", "Take picture", "Capture", "Photo", "Record", "Start recording",
        "Stop recording", "Record video", "Stop", "Video")
    val SHUTTER_IDS = listOf("com.android.camera:id/shutter_button", "com.android.camera:id/v9_shutter_button_split",
        "com.android.camera:id/shutter_button_horizontal", "com.google.android.GoogleCamera:id/shutter_button",
        "org.codeaurora.snapcam:id/shutter_button", "com.android.camera2:id/shutter_button")

    /** Last link or text found by [scan]; "open the link" uses it. */
    @Volatile var lastLink: String = ""

    /** Countdown seconds for a mode: photos and selfies wait 3 s by default so the user can get ready; video starts at once. */
    fun delayFor(mode: String, asked: String?): Int {
        val n = asked?.trim()?.toIntOrNull()
        if (n != null) return n.coerceIn(0, MAX_DELAY_S)
        return if (mode == "video") 0 else DEFAULT_PHOTO_DELAY_S
    }

    /** Spoken numbers for a countdown: 3 → ["3", "2", "1"]. Long waits only count the last 3. */
    fun countdown(seconds: Int): List<String> = (seconds downTo 1).map { it.toString() }

    /** Newest name in `ls -t` output, ignoring hidden/temp files. */
    fun newest(lsOut: String): String? = lsOut.lines().map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith(".") && !it.endsWith(".tmp") && !it.startsWith("ls:") }

    fun doneSay(mode: String): String = when (mode) {
        "selfie" -> "Selfie taken."
        "video" -> "Recording. Say 'stop recording' when you're done."
        "stop" -> "Video saved."
        else -> "Photo taken."
    }

    /** What a QR code says, in speech. [raw] is the text, [kind] a [Barcode] value type name. */
    fun describe(raw: String, kind: String = ""): String {
        val text = raw.trim()
        if (text.startsWith("WIFI:", ignoreCase = true)) {
            val ssid = Regex("(?i)(?:^|;)WIFI:.*?S:((?:\\\\.|[^;])*)|(?:^|;)S:((?:\\\\.|[^;])*)").find(text)
                ?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.replace("\\", "")
            return if (ssid.isNullOrBlank()) "It's a Wi-Fi code." else "It's a Wi-Fi code for the network $ssid."
        }
        link(text)?.let { url ->
            val host = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: url
            return "It's a link to $host. Say 'open the link' to open it."
        }
        if (text.startsWith("tel:", true)) return "It's a phone number: ${text.substring(4)}."
        if (text.startsWith("BEGIN:VCARD", true)) {
            val name = Regex("(?im)^FN:(.+)$").find(text)?.groupValues?.get(1)?.trim()
            return if (name.isNullOrBlank()) "It's a contact card." else "It's a contact card for $name."
        }
        if (kind == "product" || text.matches(Regex("\\d{8,14}"))) return "It's a product barcode: $text."
        val one = text.replace(Regex("\\s+"), " ")
        return "The code says: ${if (one.length > ClipboardText.MAX_SAY) one.take(ClipboardText.MAX_SAY) + "…" else one}"
    }

    /** The web link in [text], or null. */
    fun link(text: String): String? {
        val t = text.trim()
        return when {
            t.matches(Regex("(?i)https?://\\S+")) -> t
            t.matches(Regex("(?i)www\\.\\S+\\.\\S+")) -> "https://$t"
            else -> null
        }
    }
}

fun cameraTools(context: Context): List<Tool> {
    val scanner by lazy { BarcodeScanning.getClient() }

    suspend fun newestShot(): String? {
        val r = RootShell.run("ls -t /sdcard/DCIM/Camera 2>/dev/null | head -3", timeoutMs = 3_000, log = false)
        return if (r.ok) CameraText.newest(r.out) else null
    }

    suspend fun cameraPackage(acc: JarvisAccessibilityService?, wait: Long): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < wait) {
            val pkg = acc?.appPackage()
            if (pkg != null && pkg != context.packageName && "camera" in pkg.lowercase()) return pkg
            delay(300)
        }
        return acc?.appPackage()?.takeIf { "camera" in it.lowercase() }
    }

    fun cameraIntent(mode: String): Intent {
        val locked = context.isLocked()
        val action = when {
            mode == "video" -> MediaStore.INTENT_ACTION_VIDEO_CAMERA
            locked -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
            else -> MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        return Intent(action).apply {
            if (mode == "selfie") {
                putExtra("android.intent.extras.CAMERA_FACING", 1)
                putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
                putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
            }
        }
    }

    /** Taps the shutter; falls back to the camera key, then volume up (MIUI's default shutter key). */
    suspend fun pressShutter(acc: JarvisAccessibilityService?, pkg: String?, before: String?): Boolean {
        val tapped = pkg != null && acc?.clickExact(pkg, CameraText.SHUTTER_LABELS, CameraText.SHUTTER_IDS) == true
        if (!tapped) RootInput.key(27) // KEYCODE_CAMERA
        delay(2_000)
        if (before == null) return true // no root listing: trust it
        if (newestShot() != before) return true
        if (pkg != null && acc?.appPackage() == pkg) {
            RootInput.key(24) // KEYCODE_VOLUME_UP, only while the camera app is in front
            delay(2_000)
            return newestShot() != before
        }
        return false
    }

    suspend fun decode(bmp: Bitmap): Pair<String, String>? = suspendCancellableCoroutine { cont ->
        scanner.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { codes ->
                val c = codes.firstOrNull { !it.rawValue.isNullOrBlank() }
                val kind = when (c?.valueType) { Barcode.TYPE_PRODUCT, Barcode.TYPE_ISBN -> "product"; Barcode.TYPE_URL -> "url"; else -> "" }
                if (cont.isActive) cont.resume(c?.rawValue?.let { it to kind })
            }
            .addOnFailureListener {
                JarvisLog.i("qr failed: ${it.message}")
                if (cont.isActive) cont.resume(null)
            }
    }

    suspend fun scanScreen(acc: JarvisAccessibilityService, tries: Int): Pair<String, String>? {
        repeat(tries) {
            val bmp = ScreenOcr.shot(acc)
            if (bmp != null) {
                val found = try { decode(bmp) } finally { bmp.recycle() }
                if (found != null) return found
            }
            delay(400)
        }
        return null
    }

    return listOf(
        SimpleTool(
            "take_photo",
            "Take a photo, selfie or video with the camera, or stop the video",
            listOf(
                arg("mode", "what to take", required = false, choices = listOf("photo", "selfie", "video", "stop")),
                arg("delay", "seconds to count down before the shot", required = false),
            ),
        ) { c, _ ->
            val mode = c.arg("mode") ?: "photo"
            val acc = JarvisAccessibilityService.instance
            if (mode == "stop") {
                val pkg = acc?.appPackage()?.takeIf { "camera" in it.lowercase() }
                    ?: return@SimpleTool ToolResult.fail("The camera isn't open.")
                if (!pressShutter(acc, pkg, null)) return@SimpleTool ToolResult.fail("I couldn't press stop. Tap the red button.")
                return@SimpleTool ToolResult.ok(CameraText.doneSay("stop"))
            }
            val before = newestShot()
            if (!context.launch(cameraIntent(mode))) return@SimpleTool ToolResult.fail("I can't open the camera.")
            val pkg = cameraPackage(acc, 5_000)
            if (pkg == null && acc == null) return@SimpleTool ToolResult.ok("I opened the camera. Screen control is off, so tap the shutter yourself.")
            delay(1_200) // let the preview start
            for (n in CameraText.countdown(CameraText.delayFor(mode, c.arg("delay")))) {
                JarvisCoreService.say(context, n)
                delay(1_000)
            }
            if (!pressShutter(acc, pkg, before)) return@SimpleTool ToolResult.fail("The camera is open, but I couldn't press the shutter. Tap it yourself.")
            ToolResult.ok(CameraText.doneSay(mode))
        },
        SimpleTool(
            "scan_qr",
            "Scan a QR code or barcode with the camera, or one shown on the screen, and say what it is",
            listOf(arg("source", "where the code is", required = false, choices = listOf("camera", "screen"))),
        ) { c, _ ->
            val acc = JarvisAccessibilityService.instance
                ?: return@SimpleTool ToolResult.fail("Screen control is off. Open Jarvis setup to turn on accessibility.")
            val found = if (c.arg("source") == "screen") scanScreen(acc, 1) else {
                if (!context.launch(cameraIntent("photo"))) return@SimpleTool ToolResult.fail("I can't open the camera.")
                cameraPackage(acc, 5_000)
                JarvisCoreService.say(context, "Point the camera at the code.")
                delay(1_500)
                scanScreen(acc, 12)
            } ?: return@SimpleTool ToolResult.fail(
                if (c.arg("source") == "screen") "I don't see a QR code on the screen." else "I couldn't find a code. Hold it closer and try again.",
            )
            val (raw, kind) = found
            CameraText.lastLink = CameraText.link(raw).orEmpty()
            ToolResult.ok(CameraText.describe(raw, kind), raw)
        },
        SimpleTool("open_qr_link", "Open the link from the last scanned QR code", risk = com.alrasin.jarvis.core.Risk.CONFIRM) { _, _ ->
            val url = CameraText.lastLink.ifBlank { return@SimpleTool ToolResult.fail("I haven't scanned a link yet.") }
            if (context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))) ToolResult.ok("Opened ${Uri.parse(url).host ?: "the link"}.")
            else ToolResult.fail("No browser found.")
        },
    )
}

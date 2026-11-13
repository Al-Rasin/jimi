package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.actions.a11y.replaceNodeText
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * What `input` does with root, done through the accessibility service instead (D-077): phones without root
 * still tap, swipe, press Back/Home/Recents, type and press Enter. Keys Android gives no accessibility
 * action for (volume, camera, call) still need root.
 */
object A11yInput {
    private val service get() = JarvisAccessibilityService.instance

    suspend fun tap(x: Int, y: Int): Boolean = swipe(x, y, x, y, 50)

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): Boolean {
        val s = service ?: return false
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); if (x1 != x2 || y1 != y2) lineTo(x2.toFloat(), y2.toFloat()) }
        return gesture(s, path, durationMs.toLong().coerceAtLeast(1))
    }

    fun key(code: Int): Boolean {
        val s = service ?: return false
        if (code == InputKeys.ENTER) return focusedInput(s)?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) == true
        return InputKeys.globalAction(code)?.let(s::performGlobalAction) ?: false
    }

    /** Like `input text`: adds [text] at the end of the focused box. */
    fun text(text: String): Boolean {
        val node = service?.let(::focusedInput) ?: return false
        val old = node.text?.toString().takeUnless { node.isShowingHintText }.orEmpty()
        return node.replaceNodeText(old + text)
    }

    private fun focusedInput(s: AccessibilityService): AccessibilityNodeInfo? {
        val root = s.rootInActiveWindow ?: return null
        return root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
            ?: root.findFirst { it.isEditable && it.isVisibleToUser }
    }

    suspend fun gesture(s: AccessibilityService, path: Path, durationMs: Long): Boolean = suspendCancellableCoroutine { cont ->
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        val sent = s.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { if (cont.isActive) cont.resume(false) }
        }, null)
        if (!sent && cont.isActive) cont.resume(false)
    }
}

/** Key codes → accessibility global actions (pure, unit tested). */
object InputKeys {
    const val ENTER = 66

    fun globalAction(code: Int): Int? = when (code) {
        3 -> AccessibilityService.GLOBAL_ACTION_HOME
        4 -> AccessibilityService.GLOBAL_ACTION_BACK
        187 -> AccessibilityService.GLOBAL_ACTION_RECENTS
        26, 223 -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
        83 -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
        120 -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
        else -> null
    }
}

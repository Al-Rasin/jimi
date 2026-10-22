package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.alrasin.jarvis.core.JarvisLog

/** Screen reading and tapping (PLAN 4.5). Enabled by root at setup (Settings.Secure). */
class JarvisAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        JarvisLog.i("accessibility connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    /** Visible text on screen, top to bottom. */
    fun screenText(max: Int = 4000): String {
        val root = rootInActiveWindow ?: return ""
        val out = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t) else if (d.isNotEmpty() && n.isClickable) out.add(d)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return out.distinct().joinToString("\n").take(max)
    }

    fun appPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Excludes Jimi's panel and accessibility overlays from screen-agent targets. */
    fun agentRoot(): AccessibilityNodeInfo? {
        windows.sortedByDescending { it.layer }.forEach { window ->
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
            val root = window.root ?: return@forEach
            if (root.packageName?.toString() != packageName) return root
            root.recycle()
        }
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() != packageName) return root
        root.recycle()
        return null
    }

    /** Clicks the node whose text/description best matches [text]; climbs to a clickable parent. Root tap as fallback. */
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val all = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || !n.isVisibleToUser) return
            if (!n.text.isNullOrBlank() || !n.contentDescription.isNullOrBlank()) all.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val node = Fuzzy.best(text, all, { (it.text ?: it.contentDescription).toString() }, min = 0.55) ?: return false
        return click(node)
    }

    fun clickByDescription(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = ArrayList<AccessibilityNodeInfo>()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null) return
            if (n.contentDescription?.toString().equals(desc, ignoreCase = true)) found.add(n)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        return found.firstOrNull()?.let(::click) ?: false
    }

    fun clickById(id: String): Boolean =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let(::click) ?: false

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect().also(node::getBoundsInScreen)
        return Runtime.getRuntime().exec(arrayOf("su", "-c", "input tap ${r.centerX()} ${r.centerY()}")).waitFor() == 0
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.isEditable && n.isVisibleToUser) return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            target = find(root)
        }
        target ?: return false
        val old = target.text?.toString().takeUnless { target.isShowingHintText }.orEmpty()
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, if (old.isEmpty()) text else "$old $text")
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (n == null) return null
            if (n.isScrollable && n.isVisibleToUser) return n
            for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
            return null
        }
        val s = find(root) ?: return false
        return s.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun global(action: Int) = performGlobalAction(action)
    fun lockScreen() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
        const val COMPONENT = "com.alrasin.jarvis/com.alrasin.jarvis.actions.JarvisAccessibilityService"
    }
}

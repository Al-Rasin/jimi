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

    /** Active window of [packageName], else its top on-screen window (Messenger chat heads are not the active window). */
    private fun rootFor(packageName: String): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }?.let { return it }
        return runCatching { windows }.getOrDefault(emptyList()).sortedByDescending { it.layer }
            .firstNotNullOfOrNull { w -> w.root?.takeIf { it.packageName?.toString() == packageName } }
    }

    fun hasWindow(packageName: String): Boolean = rootFor(packageName) != null

    /** WhatsApp "Message yourself" chat: title "<name> (You)" or the "Message yourself" subtitle. */
    fun isSelfChat(packageName: String): Boolean = visibleNodes(packageName).any { n ->
        val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
        val t = n.text?.toString()?.trim().orEmpty()
        (id == "conversation_contact_status" && t.equals("Message yourself", true)) ||
            (id == "conversation_contact_name" && t.endsWith("(You)"))
    }

    private fun visibleNodes(packageName: String): List<AccessibilityNodeInfo> {
        val root = rootFor(packageName) ?: return emptyList()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || nodes.size >= 1500 || !node.isVisibleToUser ||
                node.packageName?.toString() != packageName) return
            nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return nodes
    }

    private fun clickableParent(node: AccessibilityNodeInfo, packageName: String): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(12) {
            val n = current ?: return null
            if (n.packageName?.toString() != packageName || !n.isVisibleToUser || !n.isEnabled) return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    fun clickExact(packageName: String, labels: List<String> = emptyList(), ids: List<String> = emptyList()): Boolean {
        val targets = visibleNodes(packageName).filter { n ->
            !n.isEditable && (n.viewIdResourceName in ids || labels.any {
                n.text?.toString()?.trim().equals(it, true) || n.contentDescription?.toString()?.trim().equals(it, true)
            })
        }.mapNotNull { clickableParent(it, packageName) }.distinct()
        return targets.singleOrNull()?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    fun clickUniqueText(packageName: String, text: String): Boolean = clickExact(packageName, listOf(text))

    fun hasExactText(packageName: String, text: String): Boolean = visibleNodes(packageName).any {
        !it.isEditable && (it.text?.toString()?.trim().equals(text, true) ||
            it.contentDescription?.toString()?.trim().equals(text, true))
    }

    fun hasConversationTitle(packageName: String, text: String): Boolean = visibleNodes(packageName).any { node ->
        val expectedNumber = ContactResolver.number(text)
        val exact = !node.isEditable && listOf(node.text, node.contentDescription).any { value ->
            val label = value?.toString()?.trim().orEmpty()
            label.equals(text, true) || (expectedNumber != null && ContactResolver.number(label) == expectedNumber)
        }
        if (!exact) return@any false
        val titleIds = setOf("conversation_contact_name", "thread_name", "thread_title", "thread_title_name")
        if (node.viewIdResourceName?.substringAfter(":id/") in titleIds) return@any true
        var ancestor = node.parent
        repeat(5) {
            val parent = ancestor ?: return@any false
            val id = parent.viewIdResourceName.orEmpty().substringAfter(":id/")
            if (id.contains("toolbar") || id.contains("action_bar") || id == "thread_header") return@any true
            ancestor = parent.parent
        }
        false
    }

    private fun editable(packageName: String): AccessibilityNodeInfo? {
        val nodes = visibleNodes(packageName).filter { it.isEditable && it.isEnabled }
        return nodes.singleOrNull { it.isFocused } ?: nodes.singleOrNull()
    }

    fun editableText(packageName: String): String? = editable(packageName)?.let {
        if (it.isShowingHintText) "" else it.text?.toString().orEmpty()
    }

    fun replaceText(packageName: String, text: String): Boolean = editable(packageName)?.performAction(
        AccessibilityNodeInfo.ACTION_SET_TEXT,
        Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
    ) ?: false

    fun clickFirstMediaResult(packageName: String): Boolean {
        val candidates = visibleNodes(packageName).filter { node ->
            MediaResultSelector.isResult(packageName, node.viewIdResourceName.orEmpty(),
                node.text?.toString().orEmpty(), node.contentDescription?.toString().orEmpty())
        }
        val target = candidates.firstNotNullOfOrNull { clickableParent(it, packageName) } ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

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

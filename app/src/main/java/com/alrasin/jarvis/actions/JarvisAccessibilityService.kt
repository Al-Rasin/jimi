package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.alrasin.jarvis.actions.a11y.findAll
import com.alrasin.jarvis.actions.a11y.findFirst
import com.alrasin.jarvis.actions.a11y.label
import com.alrasin.jarvis.actions.a11y.scroll
import com.alrasin.jarvis.actions.a11y.replaceNodeText
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

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
        val out = root.findAll { it.isVisibleToUser }.mapNotNull { n ->
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) t else if (d.isNotEmpty() && n.isClickable) d else null
        }
        return out.distinct().joinToString("\n").take(max)
    }

    fun appPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** Active window of [packageName], else all its on-screen windows (Messenger chat heads use several, none active). */
    private fun rootsFor(packageName: String): List<AccessibilityNodeInfo> {
        rootInActiveWindow?.takeIf { it.packageName?.toString() == packageName }?.let { return listOf(it) }
        return runCatching { windows }.getOrDefault(emptyList()).sortedByDescending { it.layer }
            .mapNotNull { w -> w.root?.takeIf { it.packageName?.toString() == packageName } }
    }

    fun hasWindow(packageName: String): Boolean = rootsFor(packageName).isNotEmpty()

    /** WhatsApp "Message yourself" chat: title "<name> (You)" or the "Message yourself" subtitle. */
    fun isSelfChat(packageName: String): Boolean = visibleNodes(packageName).any { n ->
        val id = n.viewIdResourceName.orEmpty().substringAfter(":id/")
        val t = n.text?.toString()?.trim().orEmpty()
        (id == "conversation_contact_status" && t.equals("Message yourself", true)) ||
            (id == "conversation_contact_name" && t.endsWith("(You)"))
    }

    private fun visibleNodes(packageName: String): List<AccessibilityNodeInfo> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40 || nodes.size >= 1500 || !node.isVisibleToUser ||
                node.packageName?.toString() != packageName) return
            nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
        }
        rootsFor(packageName).forEach { walk(it, 0) }
        return nodes
    }

    /** Non-editable visible texts of [packageName], top to bottom. */
    fun texts(packageName: String): List<String> = visibleNodes(packageName)
        .filter { !it.isEditable }.mapNotNull { it.text?.toString()?.trim()?.ifEmpty { null } }.distinct()

    /** The on/off switch in the row labelled [label]: true/false, or null when the row has no switch. */
    fun switchState(packageName: String, label: String): Boolean? = switchIn(rowOf(packageName, label))?.isChecked

    /** Clicks the row labelled [label] if its switch is not already [on]. True when it is [on] afterwards (or clicked). */
    fun setSwitch(packageName: String, label: String, on: Boolean): Boolean {
        val row = rowOf(packageName, label) ?: return false
        val sw = switchIn(row) ?: return false
        if (sw.isChecked == on) return true
        return (if (sw.isClickable) sw else row).performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun rowOf(packageName: String, label: String): AccessibilityNodeInfo? = visibleNodes(packageName)
        .filter { !it.isEditable && it.text?.toString()?.trim().equals(label, true) }
        .mapNotNull { clickableParent(it, packageName) }.distinct().singleOrNull()

    private fun switchIn(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? = node.findFirst { it.isCheckable }

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

    /** Taps and focuses the message box; Messenger ignores set-text until its box has focus. */
    fun focusEditable(packageName: String): Boolean = editable(packageName)?.let {
        it.performAction(AccessibilityNodeInfo.ACTION_CLICK) or it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    } ?: false

    fun replaceText(packageName: String, text: String): Boolean = editable(packageName)?.replaceNodeText(text) ?: false

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
    suspend fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val all = root.findAll { it.isVisibleToUser && (!it.text.isNullOrBlank() || !it.contentDescription.isNullOrBlank()) }
        val node = Fuzzy.best(text, all, { it.label() }, min = 0.55) ?: return false
        return click(node)
    }

    suspend fun clickByDescription(desc: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val found = root.findAll { it.contentDescription?.toString().equals(desc, ignoreCase = true) }
        return found.firstOrNull()?.let { click(it) } ?: false
    }

    suspend fun clickById(id: String): Boolean =
        rootInActiveWindow?.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()?.let { click(it) } ?: false

    private suspend fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        val r = Rect().also(node::getBoundsInScreen)
        return RootInput.tap(r.centerX(), r.centerY()).ok
    }

    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        var target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (target == null || !target.isEditable) {
            target = root.findFirst { it.isEditable && it.isVisibleToUser }
        }
        target ?: return false
        val old = target.text?.toString().takeUnless { target.isShowingHintText }.orEmpty()
        return target.replaceNodeText(if (old.isEmpty()) text else "$old $text")
    }

    fun scroll(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val s = root.findFirst { it.isScrollable && it.isVisibleToUser } ?: return false
        return s.scroll(forward)
    }

    /** What is on screen now, as a software bitmap, for the panel's blurred backdrop (D-050). Never saved. */
    suspend fun screenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY, mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = result.hardwareBuffer.use { buf ->
                        Bitmap.wrapHardwareBuffer(buf, result.colorSpace)?.let { hw -> hw.copy(Bitmap.Config.ARGB_8888, false).also { hw.recycle() } }
                    }
                    cont.resume(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    JarvisLog.i("screenshot failed ($errorCode)")
                    cont.resume(null)
                }
            },
        )
    }

    fun global(action: Int) = performGlobalAction(action)
    fun lockScreen() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    companion object {
        @Volatile var instance: JarvisAccessibilityService? = null
            private set
        const val COMPONENT = "com.alrasin.jarvis/com.alrasin.jarvis.actions.JarvisAccessibilityService"
    }
}

package com.alrasin.jarvis.agent

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.alrasin.jarvis.actions.AppList
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.core.openApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class AccessibilityAgentScreen(private val context: Context) : AgentScreen {
    private val apps = AppList(context)

    override suspend fun snapshot(): ScreenSnapshot = withContext(Dispatchers.Main.immediate) {
        if (context.isLocked()) return@withContext ScreenSnapshot("", emptyList())
        val root = JarvisAccessibilityService.instance?.agentRoot()
            ?: return@withContext ScreenSnapshot("", emptyList())
        try { capture(root) } finally { root.recycle() }
    }

    override suspend fun perform(action: AgentAction, snapshot: ScreenSnapshot): Boolean {
        if (action == AgentAction.Wait) { delay(500); return true }
        val result = withContext(Dispatchers.Main.immediate) {
            val actionContext = currentCoroutineContext()
            actionContext.ensureActive()
            if (context.isLocked()) return@withContext false
            val service = JarvisAccessibilityService.instance ?: return@withContext false
            if (action is AgentAction.OpenApp) {
                actionContext.ensureActive()
                return@withContext context.openApp(apps, action.packageName) { actionContext.ensureActive() }
            }
            val root = service.agentRoot() ?: return@withContext false
            try {
                if (capture(root) != snapshot) return@withContext false
                actionContext.ensureActive()
                when (action) {
                    is AgentAction.Tap -> target(root, snapshot, action.element) { node ->
                        actionContext.ensureActive()
                        node.isEnabled && node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }
                    is AgentAction.Type -> target(root, snapshot, action.element) { node ->
                        actionContext.ensureActive()
                        node.isEnabled && node.isEditable && node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
                            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, action.text) })
                    }
                    is AgentAction.Scroll -> scroll(root, action.direction) { actionContext.ensureActive() }
                    AgentAction.Back -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    AgentAction.Home -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    else -> false
                }
            } finally { root.recycle() }
        }
        if (result) delay(250)
        return result
    }

    private fun capture(root: AccessibilityNodeInfo): ScreenSnapshot {
        val elements = mutableListOf<ScreenElement>()
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (++visited > 600 || depth > 30 || elements.size >= 60 || !node.isVisibleToUser) return
            val text = if (node.isPassword) "[redacted]" else node.text?.toString().orEmpty()
            val description = if (node.isPassword) "password field" else
                listOfNotNull(node.contentDescription, node.hintText).joinToString(" ")
            val labels = if (node.isClickable) descendantLabels(node) else Labels("", "", false)
            if (text.isNotBlank() || description.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable) {
                val bounds = Rect().also(node::getBoundsInScreen)
                elements += ScreenElement(
                    index = elements.size + 1, text = listOf(text, labels.text).filter(String::isNotBlank).distinct().joinToString(" "),
                    description = listOf(description, labels.ids).filter(String::isNotBlank).joinToString(" "), resourceId = node.viewIdResourceName.orEmpty(),
                    clickable = node.isClickable, editable = node.isEditable,
                    bounds = "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}",
                    password = node.isPassword, nodePath = path,
                    safetyUncertain = labels.incomplete || (node.isClickable && text.isBlank() && description.isBlank() && labels.text.isBlank()),
                )
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { walk(child, "$path/$i", depth + 1) } finally { child.recycle() }
            }
        }
        walk(root, "root:${root.windowId}", 0)
        return ScreenSnapshot.compact(root.packageName?.toString().orEmpty(), elements)
    }

    private data class Labels(val text: String, val ids: String, val incomplete: Boolean)

    private fun descendantLabels(node: AccessibilityNodeInfo): Labels {
        val labels = mutableListOf<String>()
        val ids = mutableListOf<String>()
        var visited = 0
        var incomplete = false
        fun walk(current: AccessibilityNodeInfo, depth: Int) {
            if (++visited > 100 || depth > 12) { incomplete = true; return }
            if (!current.isVisibleToUser) return
            if (!current.isPassword) {
                current.text?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
                current.contentDescription?.toString()?.takeIf(String::isNotBlank)?.let(labels::add)
                current.viewIdResourceName?.takeIf(String::isNotBlank)?.let(ids::add)
            }
            for (i in 0 until current.childCount) {
                val child = current.getChild(i) ?: continue
                try { walk(child, depth + 1) } finally { child.recycle() }
            }
        }
        walk(node, 0)
        return Labels(labels.distinct().joinToString(" "), ids.distinct().joinToString(" "), incomplete)
    }

    private fun target(root: AccessibilityNodeInfo, snapshot: ScreenSnapshot, index: Int,
        act: (AccessibilityNodeInfo) -> Boolean): Boolean {
        val element = snapshot.elements.firstOrNull { it.index == index } ?: return false
        val path = element.nodePath.split('/')
        if (path.first() != "root:${root.windowId}") return false
        fun descend(node: AccessibilityNodeInfo, offset: Int): Boolean {
            if (offset == path.size) return node.isVisibleToUser && act(node)
            val childIndex = path[offset].toIntOrNull() ?: return false
            val child = node.getChild(childIndex) ?: return false
            return try { descend(child, offset + 1) } finally { child.recycle() }
        }
        return descend(root, 1)
    }

    private fun scroll(root: AccessibilityNodeInfo, direction: String, beforeAction: () -> Unit): Boolean {
        val action = when (direction) {
            "up" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_UP.id
            "down" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id
            "left" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_LEFT.id
            "right" -> AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_RIGHT.id
            else -> return false
        }
        var visited = 0
        fun walk(node: AccessibilityNodeInfo, depth: Int): Boolean {
            if (++visited > 600 || depth > 30 || !node.isVisibleToUser) return false
            if (node.isScrollable && node.isEnabled) {
                beforeAction()
                if (node.performAction(action)) return true
                if (direction == "up" || direction == "down") {
                    val fallback = if (direction == "down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    beforeAction()
                    if (node.performAction(fallback)) return true
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                try { if (walk(child, depth + 1)) return true } finally { child.recycle() }
            }
            return false
        }
        return walk(root, 0)
    }
}

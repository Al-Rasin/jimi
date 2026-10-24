package com.alrasin.jarvis.actions.a11y

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Depth-first search helpers shared by the accessibility service and the screen agent.
 * Replaces the hand-rolled recursive `walk`/`find` closures that were duplicated across
 * JarvisAccessibilityService.kt (ROADMAP §L.2).
 */
fun AccessibilityNodeInfo?.findAll(maxDepth: Int = Int.MAX_VALUE, test: (AccessibilityNodeInfo) -> Boolean): List<AccessibilityNodeInfo> {
    val out = ArrayList<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > maxDepth) return
        if (test(node)) out.add(node)
        for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
    }
    walk(this, 0)
    return out
}

fun AccessibilityNodeInfo?.findFirst(maxDepth: Int = Int.MAX_VALUE, test: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
    if (this == null || maxDepth < 0) return null
    if (test(this)) return this
    for (i in 0 until childCount) {
        getChild(i).findFirst(maxDepth - 1, test)?.let { return it }
    }
    return null
}

/** Visible label: node text, else content description, trimmed. */
fun AccessibilityNodeInfo.label(): String =
    text?.toString()?.trim().orEmpty().ifEmpty { contentDescription?.toString()?.trim().orEmpty() }

/** Replaces the node's text via ACTION_SET_TEXT. Named to avoid colliding with the API 33+ builder method `setText`. */
fun AccessibilityNodeInfo.replaceNodeText(text: CharSequence): Boolean = performAction(
    AccessibilityNodeInfo.ACTION_SET_TEXT,
    Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) },
)

/** Scrolls this node forward (down/right) or backward (up/left). */
fun AccessibilityNodeInfo.scroll(forward: Boolean): Boolean =
    performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

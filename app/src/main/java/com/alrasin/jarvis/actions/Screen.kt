package com.alrasin.jarvis.actions

/**
 * JarvisAccessibilityService.instance, or runs [onMissing] (which must return) when screen control is off.
 * Replaces the repeated `JarvisAccessibilityService.instance ?: return ToolResult.fail("Enable Jimi accessibility…")`
 * checks (ROADMAP §L.3). Each call site keeps its own wording via [onMissing].
 */
inline fun requireScreen(onMissing: () -> Nothing): JarvisAccessibilityService =
    JarvisAccessibilityService.instance ?: onMissing()

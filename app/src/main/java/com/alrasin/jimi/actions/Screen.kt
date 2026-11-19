package com.alrasin.jimi.actions

import com.alrasin.jimi.core.pollUntil

/**
 * JimiAccessibilityService.instance, or runs [onMissing] (which must return) when screen control is off.
 * Replaces the repeated `JimiAccessibilityService.instance ?: return ToolResult.fail("Enable Jimi accessibility…")`
 * checks (ROADMAP §L.3). Each call site keeps its own wording via [onMissing].
 * MIUI sometimes drops the service; with root it is turned on again first (§M.9).
 */
suspend inline fun requireScreen(onMissing: () -> Nothing): JimiAccessibilityService =
    JimiAccessibilityService.instance ?: reviveScreen() ?: onMissing()

/** Re-enables the accessibility service through root and waits up to 3 s for it to connect. */
suspend fun reviveScreen(): JimiAccessibilityService? {
    if (!RootShell.available || !RootSetup.enableAccessibility(restart = true)) return null
    pollUntil(15, 200) { JimiAccessibilityService.instance != null }
    return JimiAccessibilityService.instance
}

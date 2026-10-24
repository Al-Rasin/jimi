package com.alrasin.jarvis.core

import kotlinx.coroutines.delay

/**
 * Tries [check] up to [tries] times, waiting [pauseMs] between tries, until it returns true.
 * [delayFirst] = true waits before the first try too (for a screen/value that needs to settle first).
 * Replaces the hand-rolled wait loops across the actions/agent code (ROADMAP §L.1).
 */
suspend fun pollUntil(tries: Int, pauseMs: Long, delayFirst: Boolean = false, check: suspend () -> Boolean): Boolean {
    repeat(tries) {
        if (delayFirst) delay(pauseMs)
        if (check()) return true
        if (!delayFirst) delay(pauseMs)
    }
    return false
}

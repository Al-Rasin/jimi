package com.alrasin.jimi.service

/**
 * While another app holds the mic, a fresh recorder is tried after 3, 6, 12, 24, then every 30 s,
 * instead of every 3 s forever. [recovered] (audio came through) starts again at 3 s.
 */
internal class SilencedReopenBackoff {
    private var since: Long? = null
    private var reopens = 0

    /** True between the first silenced read and [recovered]. */
    val waiting: Boolean get() = since != null

    /** Called on each silenced read with the current time; true = close the recorder now so the next read reopens it. */
    fun shouldReopen(nowMs: Long): Boolean {
        val start = since ?: run { since = nowMs; return false }
        if (nowMs - start < waitMs()) return false
        since = nowMs
        reopens++
        return true
    }

    /** Wait before the next reopen. */
    fun waitMs(): Long = (FIRST_MS shl reopens.coerceAtMost(4)).coerceAtMost(MAX_MS)

    fun recovered() {
        since = null
        reopens = 0
    }

    private companion object {
        const val FIRST_MS = 3_000L
        const val MAX_MS = 30_000L
    }
}

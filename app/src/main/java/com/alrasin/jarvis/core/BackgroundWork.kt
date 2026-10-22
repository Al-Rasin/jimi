package com.alrasin.jarvis.core

import android.os.Process

/** Restore priority before returning a pooled worker to unrelated work. */
inline fun <T> backgroundWork(block: () -> T): T {
    val thread = Process.myTid()
    val previous = Process.getThreadPriority(thread)
    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
    return try { block() } finally { Process.setThreadPriority(previous) }
}

package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.JarvisLog
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Root commands via libsu (D-009, PLAN 4.1). Every command is logged; slow ones time out. */
object RootShell {
    data class Result(val ok: Boolean, val out: String)

    init {
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER).setTimeout(10))
    }

    val available: Boolean get() = runCatching { Shell.getShell().isRoot }.getOrDefault(false)

    suspend fun run(vararg cmds: String, timeoutMs: Long = 8_000): Result = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(timeoutMs) { Shell.cmd(*cmds).exec() }
        val res = if (r == null) Result(false, "timeout") else Result(r.isSuccess, (r.out + r.err).joinToString("\n"))
        JarvisLog.i("root: ${cmds.joinToString("; ").take(120)} → ${if (res.ok) "ok" else "fail ${res.out.take(80)}"}")
        res
    }

    /** Quote for sh: 'it'\''s'. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

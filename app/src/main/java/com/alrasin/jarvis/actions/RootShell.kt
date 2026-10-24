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

    /** [log] false: command output holds private data; only ok/fail is logged. */
    suspend fun run(vararg cmds: String, timeoutMs: Long = 8_000, log: Boolean = true): Result = withContext(Dispatchers.IO) {
        val r = withTimeoutOrNull(timeoutMs) { Shell.cmd(*cmds).exec() }
        val res = if (r == null) Result(false, "timeout") else Result(r.isSuccess, (r.out + r.err).joinToString("\n"))
        JarvisLog.i("root: ${cmds.joinToString("; ").take(120)} → ${if (res.ok) "ok" else if (log) "fail ${res.out.take(80)}" else "fail"}")
        res
    }

    /** Quote for sh: 'it'\''s'. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/** `input` commands via root (ROADMAP §L.4). Dedupes the raw `RootShell.run("input ...")` calls across actions/. */
object RootInput {
    suspend fun tap(x: Int, y: Int): RootShell.Result = RootShell.run("input tap $x $y")
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250): RootShell.Result =
        RootShell.run("input swipe $x1 $y1 $x2 $y2 $durationMs")
    suspend fun key(code: Int): RootShell.Result = RootShell.run("input keyevent $code")
    suspend fun text(text: String): RootShell.Result = RootShell.run("input text ${RootShell.quote(text)}")
}

/** `settings get/put/list` via root (ROADMAP §L.4). Dedupes the raw `RootShell.run("settings ...")` calls across actions/. */
object RootSettings {
    suspend fun get(ns: String, key: String): String = RootShell.run("settings get $ns $key", log = false).out.trim()
    suspend fun put(ns: String, key: String, value: String): Boolean = RootShell.run("settings put $ns $key $value").ok
    suspend fun list(ns: String): List<String> = RootShell.run("settings list $ns", log = false).out.lines()
}

package com.alrasin.jarvis.actions

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.Settings
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
        val shown = if (log) cmds.joinToString("; ").take(120) else cmds.joinToString("; ") { it.substringBefore(' ') + " <hidden>" }
        JarvisLog.i("root: $shown → ${if (res.ok) "ok" else if (log) "fail ${res.out.take(80)}" else "fail"}")
        res
    }

    /** Quote for sh: 'it'\''s'. */
    fun quote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}

/**
 * `input` commands via root (ROADMAP §L.4); without root (or if root fails) the accessibility service does
 * the same thing (D-077). ok=false only when neither worked.
 */
object RootInput {
    suspend fun tap(x: Int, y: Int): RootShell.Result = run("input tap $x $y") { A11yInput.tap(x, y) }
    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 250): RootShell.Result =
        run("input swipe $x1 $y1 $x2 $y2 $durationMs") { A11yInput.swipe(x1, y1, x2, y2, durationMs) }
    suspend fun key(code: Int): RootShell.Result = run("input keyevent $code") { A11yInput.key(code) }
    suspend fun text(text: String): RootShell.Result = run("input text ${RootShell.quote(text)}", log = false) { A11yInput.text(text) }

    private suspend fun run(cmd: String, log: Boolean = true, fallback: suspend () -> Boolean): RootShell.Result {
        val root = withContext(Dispatchers.IO) { RootShell.available }
        if (root) RootShell.run(cmd, log = log).let { if (it.ok) return it }
        val ok = runCatching { fallback() }.getOrDefault(false)
        JarvisLog.i("input (no root): ${if (log) cmd.take(60) else "input text"} → ${if (ok) "a11y ok" else "fail"}")
        return RootShell.Result(ok, if (ok) "a11y" else "no root, no accessibility")
    }
}

/**
 * `settings get/put/list` via root (ROADMAP §L.4). Without root, reads go through Android's settings provider
 * and writes work where Android allows it (system keys with "modify system settings", secure/global only if
 * WRITE_SECURE_SETTINGS was granted with adb) (D-077).
 */
object RootSettings {
    @Volatile private var app: Context? = null

    fun init(context: Context) { app = context.applicationContext }

    suspend fun get(ns: String, key: String): String {
        if (withContext(Dispatchers.IO) { RootShell.available }) return RootShell.run("settings get $ns $key", log = false).out.trim()
        return withContext(Dispatchers.IO) { app?.let { AndroidSettings.get(it.contentResolver, ns, key) } ?: "null" }
    }

    suspend fun put(ns: String, key: String, value: String): Boolean {
        if (withContext(Dispatchers.IO) { RootShell.available } && RootShell.run("settings put $ns $key $value").ok) return true
        return withContext(Dispatchers.IO) { app?.let { AndroidSettings.put(it, ns, key, value) } ?: false }
    }

    suspend fun list(ns: String): List<String> {
        if (withContext(Dispatchers.IO) { RootShell.available }) return RootShell.run("settings list $ns", log = false).out.lines()
        return withContext(Dispatchers.IO) { app?.let { AndroidSettings.list(it.contentResolver, ns) } ?: emptyList() }
    }
}

/** Settings provider without root. Android 12+ blocks some hidden keys; those read as "null". */
internal object AndroidSettings {
    fun get(cr: ContentResolver, ns: String, key: String): String = runCatching {
        when (ns) {
            "system" -> Settings.System.getString(cr, key)
            "secure" -> Settings.Secure.getString(cr, key)
            "global" -> Settings.Global.getString(cr, key)
            else -> null
        }
    }.getOrNull() ?: "null"

    fun put(context: Context, ns: String, key: String, value: String): Boolean = runCatching {
        val cr = context.contentResolver
        when (ns) {
            "system" -> Settings.System.canWrite(context) && Settings.System.putString(cr, key, value)
            "secure" -> Settings.Secure.putString(cr, key, value)
            "global" -> Settings.Global.putString(cr, key, value)
            else -> false
        }
    }.getOrDefault(false)

    fun list(cr: ContentResolver, ns: String): List<String> = runCatching {
        cr.query(Uri.parse("content://settings/$ns"), arrayOf("name", "value"), null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add("${c.getString(0)}=${c.getString(1)}") }
        }
    }.getOrNull().orEmpty()
}

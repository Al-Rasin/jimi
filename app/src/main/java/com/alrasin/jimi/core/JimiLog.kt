package com.alrasin.jimi.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Single log wrapper: logcat + last lines kept in memory for the debug view + one file per day
 * (files/logs/yyyyMMdd.log) so a later review can read what happened over days of real use.
 */
object JimiLog {
    private const val TAG = "Jimi"
    private const val MAX_LINES = 200
    private const val KEEP_DAYS = 14
    private const val MAX_FILE_BYTES = 5L * 1024 * 1024   // a runaway loop must not fill the phone
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val day = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "jimi-log").apply { isDaemon = true } }
    @Volatile private var started = false
    @Volatile private var dir: File? = null

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    /** Starts the file log and records crashes; call once from Application.onCreate. */
    fun init(context: Context) {
        started = true
        writer.execute {   // runs before any queued line: the executor keeps order
            runCatching {
                val logs = File(context.getExternalFilesDir(null), "logs")
                dir = logs
                logs.mkdirs()
                logs.listFiles().orEmpty().sortedByDescending { it.name }.drop(KEEP_DAYS).forEach { it.delete() }
            }
        }
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // Written on this thread: the process dies right after, the writer may never run.
            runCatching { append("${stamp()} E CRASH in ${thread.name}: ${Log.getStackTraceString(e)}") }
            old?.uncaughtException(thread, e)
        }
        i("app start")
    }

    fun i(msg: String) = add("I", msg).also { Log.i(TAG, msg) }

    fun e(msg: String, t: Throwable? = null) =
        add("E", msg + (t?.let { ": ${it.message}" } ?: "")).also { Log.e(TAG, msg, t) }

    private fun add(level: String, msg: String) {
        val line = "${stamp()} $level $msg"
        _lines.update { (it + line).takeLast(MAX_LINES) }
        if (started) writer.execute { runCatching { append(line) } }
    }

    private fun stamp() = synchronized(time) { time.format(Date()) }

    private fun append(line: String) {
        val logs = dir ?: return
        val file = File(logs, synchronized(day) { day.format(Date()) } + ".log")
        if (file.length() < MAX_FILE_BYTES) file.appendText(line + "\n")
    }
}

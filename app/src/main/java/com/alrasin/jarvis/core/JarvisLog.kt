package com.alrasin.jarvis.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Single log wrapper: logcat + last lines kept in memory for the debug view. */
object JarvisLog {
    private const val TAG = "Jarvis"
    private const val MAX_LINES = 200
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    fun i(msg: String) = add("I", msg).also { Log.i(TAG, msg) }

    fun e(msg: String, t: Throwable? = null) =
        add("E", msg + (t?.let { ": ${it.message}" } ?: "")).also { Log.e(TAG, msg, t) }

    private fun add(level: String, msg: String) {
        val line = "${time.format(Date())} $level $msg"
        _lines.update { (it + line).takeLast(MAX_LINES) }
    }
}

package com.alrasin.jarvis.core

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Wrong answer" reports from the panel, for later diagnosis. One JSON object per line in
 * `files/reports/wrong_answers.jsonl` (external app files, so `adb pull` works without root).
 * Each report keeps what was heard, the answer, the tool, the user's note and the recent log lines.
 */
object WrongAnswers {
    private const val LOG_LINES = 40

    fun file(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "reports/wrong_answers.jsonl")

    fun save(context: Context, turnId: Long, heard: String, reply: String, tool: String?, note: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val entry = linkedMapOf(
            "time" to time, "turnId" to turnId, "heard" to heard, "reply" to reply, "tool" to tool,
            "note" to note, "log" to JarvisLog.lines.value.takeLast(LOG_LINES),
        )
        val f = file(context)
        f.parentFile?.mkdirs()
        f.appendText(Json.write(entry) + "\n")
        JarvisLog.i("wrong answer saved (turn $turnId)")
    }

    fun count(context: Context): Int = file(context).takeIf { it.isFile }?.useLines { it.count() } ?: 0
}

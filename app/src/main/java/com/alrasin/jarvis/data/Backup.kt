package com.alrasin.jarvis.data

import android.content.Context
import com.alrasin.jarvis.core.Json
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Export (PLAN 15): all user data to one JSON file in `<external files>/export/`, so it can be copied to the
 * Mac with `adb pull` for training (PLAN 13) or backup. Secrets (PIN, keys) are never exported.
 */
@Singleton
class Backup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: JarvisDatabase,
    private val reminders: com.alrasin.jarvis.actions.ReminderScheduler,
) {
    suspend fun export(): File {
        val data = mapOf(
            "version" to 1,
            "time" to System.currentTimeMillis(),
            "conversation_turn" to db.turnDao().all().map {
                mapOf(
                    "time" to it.timeMs, "source" to it.source, "heard" to it.heard, "raw" to it.rawText, "audio" to it.audioPath,
                    "reply" to it.reply, "tool" to it.tool, "wake_id" to it.wakeId, "text_ok" to it.textOk, "correction" to it.correction,
                )
            },
            "tool_call_log" to db.toolLogDao().all().map {
                mapOf(
                    "time" to it.timeMs, "heard" to it.heard, "tool" to it.tool, "args" to Json.parse(it.argsJson),
                    "source" to it.source, "ok" to it.ok, "result" to it.result, "corrected" to it.corrected,
                )
            },
            "note" to db.noteDao().last(10_000).map { mapOf("time" to it.timeMs, "text" to it.text) },
            "reminder" to db.reminderDao().pending().map { mapOf("due" to it.dueMs, "text" to it.text, "repeat" to it.repeat) },
            "memory_fact" to db.memoryDao().all().map { mapOf("time" to it.timeMs, "text" to it.text) },
            "app_rule" to db.notificationDao().rules().map { mapOf("pkg" to it.pkg, "mode" to it.mode) },
            "routine" to db.routineDao().snapshot().map {
                mapOf("name" to it.name, "steps" to Json.parse(it.stepsJson), "enabled" to it.enabled)
            },
            "notification_log" to db.notificationDao().all().map {
                mapOf(
                    "time" to it.timeMs, "pkg" to it.pkg, "title" to it.title, "text" to it.text,
                    "important" to it.important, "reason" to it.reason, "label" to it.label,
                )
            },
        )
        val dir = File(context.getExternalFilesDir(null), "export").apply { mkdirs() }
        val f = File(dir, "jarvis_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.json")
        f.writeText(Json.write(data))
        return f
    }

    /** Merge a backup file into the phone (ROADMAP #46). Returns a short summary for the user. */
    suspend fun restore(json: String): String {
        val now = RestorePlan.Current(
            notes = db.noteDao().snapshot(),
            reminders = db.reminderDao().pending(),
            facts = db.memoryDao().all(),
            rules = db.notificationDao().rules(),
            routines = db.routineDao().snapshot(),
        )
        val plan = RestorePlan.from(json, now)
        plan.notes.forEach { db.noteDao().insert(it) }
        plan.facts.forEach { db.memoryDao().insert(it) }
        plan.rules.forEach { db.notificationDao().setRule(it) }
        plan.routines.forEach { db.routineDao().put(it) }
        plan.reminders.forEach { reminders.schedule(it.copy(id = db.reminderDao().insert(it))) }
        return plan.summary()
    }
}

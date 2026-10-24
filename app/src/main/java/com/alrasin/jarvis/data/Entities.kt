package com.alrasin.jarvis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One command and its answer (ARCHITECTURE §6). [textOk]: did STT get the words right? null = not rated.
 * [correction] = what the user really said (typed in History) → training data for STT/brain.
 */
@Entity(tableName = "conversation_turn")
data class ConversationTurn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val source: String,            // voice | typed | follow_up | proactive
    val heard: String,
    val rawText: String = "",
    val audioPath: String? = null,
    val reply: String = "",
    val tool: String? = null,
    val wakeId: Long? = null,
    val textOk: Boolean? = null,
    val correction: String? = null,
)

@Entity(tableName = "note")
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val text: String,
)

/** [repeat]: none | daily | weekly. */
@Entity(tableName = "reminder")
data class Reminder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val dueMs: Long,
    val repeat: String = "none",
    val done: Boolean = false,
)

/** Every notification seen (PLAN 7). [status]: kept | cleared (auto) | dismissed (by user). [label]: user feedback. */
@Entity(tableName = "notification_log", indices = [Index("timeMs")])
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val pkg: String,
    val appName: String,
    val title: String,
    val text: String,
    val important: Boolean,
    val reason: String,
    val status: String,
    val label: String? = null,
)

/** Per-app notification rule. [mode]: keep | clear. */
@Entity(tableName = "app_rule")
data class AppRule(
    @PrimaryKey val pkg: String,
    val mode: String,
)

@Entity(tableName = "memory_fact")
data class MemoryFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val text: String,
)

/** Training data from day 1 (PLAN 5.5): text heard → tool call → result. [source]: rule | llm | cloud. */
@Entity(tableName = "tool_call_log")
data class ToolCallLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timeMs: Long,
    val heard: String,
    val tool: String,
    val argsJson: String,
    val source: String,
    val ok: Boolean,
    val result: String,
    val corrected: String? = null,
)

/** A routine = named list of tool calls ([stepsJson] = JSON array of {"tool","args"}). */
@Entity(tableName = "routine")
data class Routine(
    @PrimaryKey val name: String,
    val stepsJson: String,
    val enabled: Boolean = true,
)

/**
 * A saved recipe (ROADMAP §H.5): steps the screen agent did for one command, replayed with no AI next time.
 * [say] = spoken patterns, one per line, with {param} holes. [stepsJson] = JSON array of steps.
 * [source]: agent. [minVersion]/[maxVersion]: app versionCode range it works for (0 = any).
 */
@Entity(tableName = "recipes")
data class RecipeRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val name: String,
    val say: String,
    val stepsJson: String,
    val risky: Boolean,
    val source: String,
    val minVersion: Long,
    val maxVersion: Long,
    val timeMs: Long,
)

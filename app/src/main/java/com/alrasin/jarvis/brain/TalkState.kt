package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/** What the live conversation remembers between turns: pending questions, drafts, follow-ups, recent replies. */
internal class TalkState {
    var pending: Pair<ToolCall, Long>? = null
    var pendingQuestion: String? = null
    var contactChoice = PendingContactChoice()
    val draft = MessageDraft()
    val timeSlot = TimeSlot()
    var conversation = ConversationContext()
    var lastReply = ""
    /** Last routing step, so a crash reply can say what failed (ROADMAP §M.3). */
    var stage = "start"
    val history = ArrayDeque<Exchange>()
    /** Cloud answers only: safe to send back to the cloud as context (no tool data, D-035). */
    val cloudHistory = ArrayDeque<Exchange>()

    fun clear() {
        pending = null
        draft.clear()
        timeSlot.clear()
        cloudHistory.clear()
        contactChoice.clear()
        conversation.clear()
    }
}

internal fun monotonicMillis() = System.nanoTime() / 1_000_000

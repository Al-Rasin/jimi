package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolResult

internal object RoutineRunner {
    suspend fun run(
        steps: List<ToolCall>,
        execute: suspend (ToolCall, Boolean) -> Outcome,
        record: suspend (ToolCall, ToolResult) -> Unit,
    ): String? {
        for ((index, step) in steps.withIndex()) {
            when (val outcome = execute(step, false)) {
                is Outcome.NeedConfirm -> return "Routine stopped. ${outcome.question} Request that action separately to confirm it."
                is Outcome.Denied -> {
                    record(step, ToolResult.fail(outcome.why))
                    return "Routine stopped. ${outcome.why}"
                }
                is Outcome.Done -> record(step, outcome.result)
            }
            // Lock changes invalidate the voice and lock state captured for this command.
            if (step.tool in setOf("lock_phone", "unlock_phone") && index < steps.lastIndex) {
                return "Routine stopped after changing the phone lock. Request the remaining actions separately."
            }
        }
        return null
    }
}

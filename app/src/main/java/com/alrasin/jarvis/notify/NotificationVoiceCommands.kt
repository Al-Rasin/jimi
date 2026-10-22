package com.alrasin.jarvis.notify

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.ToolCall

/** Invoke before the general rule/model fallback in the assistant router. */
object NotificationVoiceCommands {
    fun match(input: String): ToolCall? {
        val text = RuleMatcher.normalize(input)
        return when {
            text.matches(Regex("(?:undo (?:the )?last clear|undo (?:the )?notification clear|restore (?:my |the )?(?:last )?cleared notifications)")) ->
                ToolCall("undo_notification_clear")
            text.matches(Regex("(?:why did you clear (?:that|it|those|(?:my |the )?notifications)|why was (?:that|it) cleared|explain (?:the )?last clear)")) ->
                ToolCall("explain_notification_clear")
            else -> null
        }
    }
}

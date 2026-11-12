package com.alrasin.jarvis.notify

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.core.ToolCall

// Regexes built once, not on every call (ROADMAP §L.4).
private val MATCH_RE = Regex("(?:undo (?:the )?last clear|undo (?:the )?notification clear|restore (?:my |the )?(?:last )?cleared notifications)")
private val MATCH_RE_2 = Regex("(?:why did you clear (?:that|it|those|(?:my |the )?notifications)|why was (?:that|it) cleared|explain (?:the )?last clear)")

/** Invoke before the general rule/model fallback in the assistant router. */
object NotificationVoiceCommands {
    fun match(input: String): ToolCall? {
        val text = RuleMatcher.normalize(input)
        return when {
            text.matches(MATCH_RE) ->
                ToolCall("undo_notification_clear")
            text.matches(MATCH_RE_2) ->
                ToolCall("explain_notification_clear")
            else -> null
        }
    }
}

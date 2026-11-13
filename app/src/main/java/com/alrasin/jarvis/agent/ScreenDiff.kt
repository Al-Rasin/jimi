package com.alrasin.jarvis.agent

import com.alrasin.jarvis.core.TextNorm

/**
 * "What changed" between two looks at the screen (ROADMAP #69), so the screen model can tell if its last
 * step worked instead of guessing from the new screen alone. Private fields never leave as text.
 * Pure Kotlin: unit tested.
 */
object ScreenDiff {
    private const val MAX_ITEMS = 6
    private const val MAX_CHARS = 40

    fun describe(before: ScreenSnapshot, after: ScreenSnapshot): String {
        if (before.packageName != after.packageName) return "Now in ${after.packageName.take(160)}."
        val old = labels(before)
        val new = labels(after)
        val added = new.filter { it !in old }
        val gone = old.filter { it !in new }
        if (added.isEmpty() && gone.isEmpty()) {
            return if (before.elements == after.elements) "Nothing changed on the screen." else "Only small changes on the screen."
        }
        return buildString {
            if (added.isNotEmpty()) append("New: ").append(list(added)).append(". ")
            if (gone.isNotEmpty()) append("Gone: ").append(list(gone)).append('.')
        }.trim()
    }

    /** Check step: true when the action should have changed something but the screen looks the same. */
    fun noEffect(action: AgentAction, before: ScreenSnapshot, after: ScreenSnapshot): Boolean = when (action) {
        is AgentAction.Tap, is AgentAction.Type, is AgentAction.Scroll, AgentAction.Back -> before == after
        else -> false
    }

    /** Check step for typing: the typed text should be on the screen afterwards (password fields excepted). */
    fun typedMissing(action: AgentAction, before: ScreenSnapshot, after: ScreenSnapshot): Boolean {
        if (action !is AgentAction.Type || action.text.isBlank()) return false
        if (before.elements.firstOrNull { it.index == action.element }?.password == true) return false
        val want = action.text.trim().lowercase().take(MAX_CHARS)
        return after.elements.none { it.text.lowercase().contains(want) }
    }

    private fun labels(s: ScreenSnapshot): List<String> = s.elements.asSequence()
        .filterNot { it.password }
        .map { it.text.ifBlank { it.description }.replace(TextNorm.SPACES, " ").trim().take(MAX_CHARS) }
        .filter { it.isNotEmpty() }
        .distinct().toList()

    private fun list(items: List<String>): String =
        items.take(MAX_ITEMS).joinToString(", ") { "\"$it\"" } + if (items.size > MAX_ITEMS) " and ${items.size - MAX_ITEMS} more" else ""
}

package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.actions.Fuzzy
import com.alrasin.jarvis.agent.AgentSafety
import com.alrasin.jarvis.agent.UiQuery

/**
 * Self-heal (ROADMAP §H.7) + keep maps fresh (§J.6). Pure logic — JVM tests.
 * When a saved button's selectors fail, score every on-screen element against what we saved
 * (id, label as text/desc, class) and accept only a clear winner. Opposite words never heal
 * into each other ("Log in" must not become "Log out").
 */
object SelfHeal {
    /** Edge failed this many times in a row = stale; the planner avoids it until learning sees it work again. */
    const val STALE_FAILS = 2
    /** Extra path cost of a stale edge (a normal tap costs 1000), so any fresh way wins. */
    const val STALE_COST = 5000
    /** An app update leaves its edges with this many "doubts": one more failure makes them stale. */
    const val CHECK_AGAIN_FAILS = 1

    const val MIN_SCORE = 0.6
    const val MARGIN = 0.15

    fun isStale(e: AppEdge) = e.failedCount >= STALE_FAILS

    /** Pairs (a, b): a label on one side never heals to the other. Longer phrase first when one holds the other. */
    private val OPPOSITES = listOf(
        "log in" to "log out", "login" to "logout", "sign in" to "sign out", "signin" to "signout",
        "unfollow" to "follow", "pay later" to "pay", "unmute" to "mute", "unsubscribe" to "subscribe",
        "unblock" to "block", "unlike" to "like", "unsave" to "save", "unpin" to "pin", "unarchive" to "archive",
        "enable" to "disable", "show" to "hide", "accept" to "decline", "allow" to "deny", "on" to "off",
        "add" to "remove", "open" to "close", "start" to "stop", "yes" to "no", "lock" to "unlock",
    )

    private fun has(text: String, phrase: String) = Regex("(?<![a-z])${Regex.escape(phrase)}(?![a-z])").containsMatchIn(text)

    /** 0 = left word, 1 = right word, null = neither. */
    private fun side(text: String, pair: Pair<String, String>): Int? = when {
        has(text, pair.first) -> 0
        has(text, pair.second) -> 1
        else -> null
    }

    /** True when [a] and [b] mean opposite things (log in / log out, pay / pay later, mute / unmute). */
    fun opposite(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        return OPPOSITES.any { p ->
            val sa = side(x, p)
            val sb = side(y, p)
            sa != null && sb != null && sa != sb
        }
    }

    /** What we saved about one button: its label and (if any) resource id. */
    data class Saved(val label: String, val resourceId: String = "", val className: String = "", val risk: String = "low")

    fun saved(e: AppElement, selectors: List<UiQuery.Selector>): Saved = Saved(
        label = e.label,
        resourceId = selectors.filterIsInstance<UiQuery.Selector.Id>().firstOrNull()?.resourceId.orEmpty(),
        className = selectors.filterIsInstance<UiQuery.Selector.ClassAt>().firstOrNull()?.className.orEmpty(),
        risk = e.risk,
    )

    private fun label(c: UiQuery.Candidate) = c.text.ifBlank { c.desc }

    /** 0..1 how much [c] looks like [s]. Opposite words, blank labels, or a riskier button = 0. */
    fun score(s: Saved, c: UiQuery.Candidate): Double {
        val live = label(c)
        if (live.isBlank() && c.resourceId.isBlank()) return 0.0
        if (opposite(s.label, live) || opposite(s.label, c.desc)) return 0.0
        if (s.risk == "low" && AgentSafety.riskLevel(live) != "low") return 0.0
        val sameId = s.resourceId.isNotBlank() && s.resourceId == c.resourceId
        val text = maxOf(Fuzzy.score(s.label, c.text), Fuzzy.score(s.label, c.desc))
        var score = if (sameId) 0.5 + text * 0.5 else text * 0.9
        if (s.className.isNotBlank() && s.className == c.className) score += 0.05
        if (c.clickable) score += 0.05
        return score.coerceAtMost(1.0)
    }

    /** The one clear winner, else null (unsure → teach once or hand to the AI agent). */
    fun pick(s: Saved, candidates: List<UiQuery.Candidate>): UiQuery.Candidate? {
        val ranked = candidates.map { it to score(s, it) }.filter { it.second >= MIN_SCORE }.sortedByDescending { it.second }
        val top = ranked.firstOrNull() ?: return null
        val second = ranked.drop(1).firstOrNull { label(it.first) != label(top.first) || it.first.resourceId != top.first.resourceId }
        if (second != null && top.second - second.second < MARGIN) return null
        return top.first
    }

    /** New selectors for a healed button, same shape as AppLearner.selectorsJson (id → desc → text). */
    fun healedSelectors(c: UiQuery.Candidate): List<Map<String, String>> = buildList {
        if (c.resourceId.isNotBlank()) add(mapOf("type" to "id", "value" to c.resourceId))
        if (c.desc.isNotBlank()) add(mapOf("type" to "desc", "value" to c.desc))
        if (c.text.isNotBlank()) add(mapOf("type" to "text", "value" to c.text))
    }

    /** What to do with a saved app map after an install change (§J.6). */
    enum class Fresh { SAME, CHECK_AGAIN, DELETE }

    /** [installed] null = app is gone. Saved version 0 = unknown, treated as same. */
    fun freshness(savedVersion: Long, installed: Long?): Fresh = when {
        installed == null -> Fresh.DELETE
        savedVersion != 0L && savedVersion != installed -> Fresh.CHECK_AGAIN
        else -> Fresh.SAME
    }
}

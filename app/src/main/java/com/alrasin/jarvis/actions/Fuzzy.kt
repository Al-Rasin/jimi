package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.TextNorm

// Regexes built once, not on every call (ROADMAP §L.4).
private val SCORE_RE = Regex("[^a-z0-9]+")

/** Picks the best name match for spoken text ("you tube" → "YouTube", "mom" → "Mom ❤"). */
object Fuzzy {
    fun clean(s: String) = TextNorm.alnum(s)

    /** Score 0..1 (1 = same). */
    fun score(query: String, name: String): Double {
        val q = clean(query)
        val n = clean(name)
        if (q.isEmpty() || n.isEmpty()) return 0.0
        if (q == n) return 1.0
        if (n.startsWith(q) || q.startsWith(n)) return 0.9 * minOf(q.length, n.length) / maxOf(q.length, n.length) + 0.1
        if (n.contains(q)) return 0.7 * q.length / n.length + 0.1
        // spoken word matches one word of the name ("whatsapp business" → "business")
        val words = name.lowercase().split(SCORE_RE).filter { it.isNotEmpty() }
        if (words.any { it == q }) return 0.75
        val d = distance(q, n)
        return (1.0 - d.toDouble() / maxOf(q.length, n.length)) * 0.85
    }

    fun <T> best(query: String, items: List<T>, name: (T) -> String, min: Double = 0.6): T? =
        items.map { it to score(query, name(it)) }.filter { it.second >= min }.maxByOrNull { it.second }?.first

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}

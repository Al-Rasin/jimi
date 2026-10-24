package com.alrasin.jarvis.agent.appmap

/**
 * Screen identity from structure only (toolbar, tabs, drawer, buttons) — never list rows,
 * chat text, times, or ads. Same structural parts across two visits = same screen, even if
 * list content changed.
 */
object AppFingerprint {
    fun compute(windowClass: String, structuralParts: List<String>): String {
        val parts = structuralParts.map { it.trim() }.filter { it.isNotBlank() }.toSortedSet()
        return "$windowClass::${parts.joinToString("|")}"
    }

    /** True when [a] and [b] are the same screen: same window class, >=80% of parts shared. */
    fun sameScreen(a: String, b: String, threshold: Double = 0.8): Boolean {
        val classA = a.substringBefore("::")
        val classB = b.substringBefore("::")
        if (classA != classB) return false
        val partsA = a.substringAfter("::").split("|").filter { it.isNotBlank() }.toSet()
        val partsB = b.substringAfter("::").split("|").filter { it.isNotBlank() }.toSet()
        if (partsA.isEmpty() && partsB.isEmpty()) return true
        val overlap = partsA.intersect(partsB).size.toDouble() / maxOf(partsA.size, partsB.size)
        return overlap >= threshold
    }
}

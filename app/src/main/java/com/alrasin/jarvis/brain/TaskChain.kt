package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

/**
 * "and / then" task chains (ROADMAP §H.1): "turn on wifi and then open youtube" → two known tools, run in order.
 * Each part goes through the rules alone; the app from an earlier step carries over ("open messenger, then search rahim").
 * A part that is not a task on its own is glued back to the one before it, so names like "Tom and Jerry" stay whole.
 * Pure (unit tested); [Assistant] runs the plan.
 */
object TaskChain {
    const val MAX_STEPS = 6

    data class Step(val text: String, val call: ToolCall?)

    data class Plan(val steps: List<Step>) {
        val known get() = steps.filter { it.call != null }
        val unknown get() = steps.filter { it.call == null }
        /** Every part is a known tool: run it with no AI. */
        val complete get() = unknown.isEmpty()
    }

    private val SEP = Regex("\\s*,?\\s+(?:and then|and after that|after that|and|then)\\s+|\\s*[,;]\\s*(?:then\\s+)?", RegexOption.IGNORE_CASE)
    private val LEAD = Regex("^(?:first(?:ly)?|and|then)\\s+", RegexOption.IGNORE_CASE)
    private val HAS_PLACE = Regex("\\b(?:in|on|inside)\\b", RegexOption.IGNORE_CASE)
    private val SEARCH = Regex("^(?:search|find|look for|look up)(?: for)? (.+)$", RegexOption.IGNORE_CASE)
    private val PLAY = Regex("^play (.+)$", RegexOption.IGNORE_CASE)
    private val NOT_A_STEP = setOf("cancel", "repeat_last")

    /** Parts with the separator text that came before each one (empty for the first). */
    fun split(text: String): List<Pair<String, String>> {
        val t = text.trim().trimEnd('.', '!', '?')
        val out = ArrayList<Pair<String, String>>()
        var start = 0
        var sep = ""
        for (m in SEP.findAll(t)) {
            val piece = t.substring(start, m.range.first).trim()
            if (piece.isNotEmpty()) out += sep to piece
            sep = m.value
            start = m.range.last + 1
        }
        t.substring(start).trim().takeIf { it.isNotEmpty() }?.let { out += sep to it }
        return out.map { (s, p) -> s to p.replace(LEAD, "") }
    }

    /** A plan of 2..[MAX_STEPS] parts, or null when the sentence is one task (or too long). */
    fun plan(text: String, match: (String) -> ToolCall?): Plan? {
        val pieces = split(text)
        if (pieces.size < 2) return null
        val steps = ArrayList<Step>()
        for ((sep, piece) in pieces) {
            val call = matchIn(piece, appOf(steps), match)
            if (call == null && steps.isNotEmpty()) {
                val merged = steps.last().text + sep + piece
                val mergedCall = matchIn(merged, appOf(steps.dropLast(1)), match)
                // Glue only when the extra words changed the call ("play tom" → "play tom and jerry"),
                // not when a loose rule just ignores them ("turn on wifi and order a pizza" is still only wifi).
                val used = mergedCall != null && mergedCall != steps.last().call
                if (used || (mergedCall == null && steps.last().call == null)) {
                    steps[steps.lastIndex] = Step(merged, mergedCall)
                    continue
                }
            }
            steps += Step(piece, call)
        }
        if (steps.size < 2 || steps.size > MAX_STEPS) return null
        return Plan(steps)
    }

    /** "I can turn on wifi, but not order a pizza." */
    fun partialReply(plan: Plan): String {
        val can = plan.known.joinToString(" and ") { it.text }
        val cant = plan.unknown.joinToString(" or ") { it.text }
        return "I can $can, but not $cant. Say the part I can do on its own, or turn on cloud AI for the rest."
    }

    private fun appOf(steps: List<Step>): String? = steps.asReversed().firstNotNullOfOrNull { it.call?.arg("app") }

    private fun matchIn(piece: String, app: String?, match: (String) -> ToolCall?): ToolCall? {
        if (app != null && !HAS_PLACE.containsMatchIn(piece)) {
            SEARCH.find(piece)?.let { m -> match("search ${m.groupValues[1]} in $app")?.let { return it } }
            PLAY.find(piece)?.let { m -> match("play ${m.groupValues[1]} on $app")?.let { return it } }
        }
        return match(piece)?.takeIf { it.tool !in NOT_A_STEP }
    }
}

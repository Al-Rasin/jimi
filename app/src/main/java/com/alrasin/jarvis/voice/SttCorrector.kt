package com.alrasin.jarvis.voice

import java.util.Locale

/** Corrects only explicit command slots; free text is never a spelling-correction target. */
class SttCorrector(
    contacts: Collection<String> = emptyList(),
    apps: Collection<String> = emptyList(),
    toolWords: Collection<String> = SttVocabulary.TOOL_WORDS,
) {
    private data class Entry(val text: String, val key: String, val phonetic: String)
    private data class Candidate(val entry: Entry, val distance: Int, val similarity: Double)

    private val contacts = entries(contacts)
    private val apps = entries(apps)
    private val tools = entries(toolWords)

    fun correct(heard: String): String {
        if (heard.isBlank() || heard.length > 512) return heard
        val text = HeardFixes.apply(heard)
        contactPatterns.forEach { pattern ->
            pattern.find(text)?.let { return replaceSlot(text, it.groups[1]!!, contacts) }
        }
        appPattern.find(text)?.let { return replaceSlot(text, it.groups[1]!!, apps) }
        batteryPercentagePattern.find(text)?.let { match ->
            if (tools.any { it.key == "battery" }) {
                return text.replaceRange(match.groups[1]!!.range, "percentage")
            }
        }
        mistakenTurnPattern.find(text)?.let { match ->
            val replacement = toolReplacement(match.groupValues[3]) ?: return text
            val corrected = text.replaceRange(match.groups[3]!!.range, replacement)
            return corrected.replaceRange(match.groups[1]!!.range, "turn")
        }
        soundPattern.find(text)?.let { match ->
            if (tools.none { it.key == "volume" }) return text
            if (match.groupValues[1].equals("volume", true) && !match.groupValues[2].equals("of", true)) return text
            val direction = match.groupValues[2].let { if (it.equals("of", true)) "off" else it }
            return text.replaceRange(match.groups[1]!!.range.first..match.groups[2]!!.range.last, "volume $direction")
        }
        toolPatterns.forEach { pattern ->
            pattern.find(text)?.let { match ->
                val slot = match.groups[1]!!
                val replacement = toolReplacement(slot.value) ?: return text
                var result = text.replaceRange(slot.range, replacement)
                // These two mistakes are in RuleMatcher's real-command table; never rewrite dictation.
                result = mistakenOff.replace(result) { "${it.groupValues[1]}off" }
                return result
            }
        }
        return text
    }

    private fun replaceSlot(text: String, slot: MatchGroup, vocabulary: List<Entry>): String =
        replacement(slot.value, vocabulary)?.let { text.replaceRange(slot.range, it) } ?: text

    private fun toolReplacement(heard: String): String? {
        if (tools.any { it.key == key(heard) }) return heard
        val mistake = flashlightMistakes[key(heard)]
        if (mistake != null && tools.any { it.key == mistake }) return mistake
        return replacement(heard, tools)
    }

    private fun replacement(heard: String, vocabulary: List<Entry>): String? {
        val key = key(heard)
        if (key.isEmpty() || heard.any(Char::isDigit)) return null
        if (negation.containsMatchIn(heard)) return null
        if (vocabulary.any { it.key == key }) return heard
        if (key.length < 4 || key.length > 80 || key.any { it !in 'a'..'z' }) return null
        val phonetic = phonetic(key)
        val candidates = vocabulary.mapNotNull { entry ->
            if (entry.key.length < 4 || entry.key.any { it !in 'a'..'z' }) return@mapNotNull null
            val longest = maxOf(key.length, entry.key.length)
            val limit = if (minOf(key.length, entry.key.length) >= 8) 2 else 1
            if (kotlin.math.abs(key.length - entry.key.length) > limit) return@mapNotNull null
            val distance = distance(key, entry.key, limit)
            if (distance > limit) return@mapNotNull null
            Candidate(entry, distance, 1.0 - distance.toDouble() / longest)
        }.sortedWith(compareBy<Candidate> { it.distance }.thenByDescending { it.similarity })
        val best = candidates.firstOrNull() ?: return null
        // A plausible alternative is enough to abstain, even when its phonetic key differs.
        if (candidates.drop(1).any { it.distance == best.distance || it.similarity >= best.similarity - 0.08 }) return null
        val sameSound = phonetic.isNotEmpty() && phonetic == best.entry.phonetic
        if (best.distance > 1 && !sameSound) return null
        if (!sameSound && (minOf(key.length, best.entry.key.length) < 7 || best.similarity < 0.85)) return null
        return best.entry.text
    }

    private fun entries(values: Collection<String>): List<Entry> = values.asSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 80 && it.none(Char::isDigit) }
        .distinctBy { it.lowercase(Locale.ROOT) }
        .map { Entry(it, key(it), phonetic(key(it))) }
        .toList()

    private fun key(text: String): String = text.lowercase(Locale.ROOT)
        .replace(Regex("[\\s'’\\-]+"), "")

    /** A compact English consonant key, used only alongside a tight edit-distance bound. */
    private fun phonetic(word: String): String {
        if (word.any { it !in 'a'..'z' }) return ""
        val folded = word.replace("ph", "f").replace("ck", "k").replace("qu", "k")
            .replace("sh", "s").replace("ch", "s").replace("th", "t")
        return buildString {
            folded.forEachIndexed { index, char ->
                val sound = when (char) {
                    'a', 'e', 'i', 'o', 'u', 'y' -> if (index == 0) 'a' else null
                    'h', 'w' -> null
                    'c', 'q', 'k' -> 'k'
                    'z', 's' -> 's'
                    'v', 'f' -> 'f'
                    'd', 't' -> 't'
                    'b', 'p' -> 'p'
                    else -> char
                }
                if (sound != null && lastOrNull() != sound) append(sound)
            }
        }
    }

    private fun distance(left: String, right: String, limit: Int): Int {
        var previous = IntArray(right.length + 1) { it }
        var current = IntArray(right.length + 1)
        for (i in 1..left.length) {
            current[0] = i
            var minimum = i
            for (j in 1..right.length) {
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1,
                    previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1)
                minimum = minOf(minimum, current[j])
            }
            if (minimum > limit) return limit + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.length]
    }

    companion object {
        private const val PREFIX = "^\\s*(?:(?:please|can you|could you|would you)\\s+)*"
        private const val END = "(?:\\s+please)?[.!?]?\\s*$"
        private val options = setOf(RegexOption.IGNORE_CASE)
        private val contactPatterns = listOf(
            Regex(PREFIX + "(?:call|phone|ring|dial)(?:\\s+to)?\\s+(.+?)(?:\\s+on\\s+(?:speaker|whatsapp))?" + END, options),
            Regex(PREFIX + "(?:(?:send|write)\\s+(?:a\\s+)?(?:message|text|sms)\\s+to|text|message|sms)\\s+(.+?)(?=\\s+(?:saying|that says|that)\\s+|\\s*:)", options),
        )
        private val appPattern = Regex(PREFIX + "(?:open|launch|start|run|close|quit)(?:\\s+(?:the|my))?\\s+(.+?)(?:\\s+(?:app|application))?" + END, options)
        private val toolPatterns = listOf(
            Regex(PREFIX + "(?:turn|switch)\\s+(?:on|off|of)\\s+(?:the\\s+)?(.+?)" + END, options),
            Regex(PREFIX + "(?:down|do you mean)\\s+(?:on|off)\\s+(?:the\\s+)?(.+?)" + END, options),
            Regex(PREFIX + "(?:enable|disable|activate|deactivate|toggle)\\s+(?:the\\s+)?(.+?)" + END, options),
            Regex(PREFIX + "(?:turn|switch)\\s+(?:the\\s+)?(.+?)\\s+(?:on|off|up|down)" + END, options),
            Regex(PREFIX + "(?:set|change)\\s+(?:the\\s+)?(.+?)\\s+(?:to|at)\\s+\\d+(?:\\s+percent)?" + END, options),
            Regex(PREFIX + "(?:increase|decrease|raise|lower)\\s+(?:the\\s+)?(.+?)" + END, options),
            Regex(PREFIX + "(.+?)\\s+(?:on|off|up|down)" + END, options),
            Regex(PREFIX + "(flush ?light|flesh ?light|flash ?lite|flashlights)" + END, options),
        )
        private val mistakenOff = Regex("\\b((?:turn|switch)\\s+)of\\b", RegexOption.IGNORE_CASE)
        private val negation = Regex("\\b(?:no|not|never|don['’]?t|can['’]?t|won['’]?t)\\b", RegexOption.IGNORE_CASE)
        private val soundPattern = Regex(PREFIX + "(sound|volume)\\s+(up|down|off|of|max|maximum|full)" + END, options)
        private val batteryPercentagePattern = Regex(
            PREFIX + "(?:(?:check|show|current|what is|what's|james)\\s+)?(?:the\\s+)?battery\\s+(for centers)" + END,
            options,
        )
        private val mistakenTurnPattern = Regex(
            PREFIX + "(turned|done)\\s+(on|off)\\s+(?:the\\s+)?(.+?)" + END,
            options,
        )
        private val flashlightMistakes = mapOf(
            "flushlight" to "flashlight", "fleshlight" to "flashlight",
            "flashlite" to "flashlight", "flashlights" to "flashlight",
        )
    }
}

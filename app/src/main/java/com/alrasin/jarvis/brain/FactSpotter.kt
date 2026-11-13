package com.alrasin.jarvis.brain

/**
 * Spots lasting facts the user says in plain chat ("my wife's name is Sara", "I live in Dhaka") so they can be
 * saved without "remember that …" (ROADMAP #43, §K 10.1). Questions, commands and passing states
 * ("my battery is low", "I am tired") are skipped. Returns the fact text to save, or null.
 */
object FactSpotter {
    private const val MAX_WORDS = 16

    private val QUESTION_START = Regex(
        "^(?:what|who|whom|whose|where|when|why|how|which|do|does|did|is|are|was|were|can|could|will|would|should|shall|may|am)\\b",
    )
    private val PATTERNS = listOf(
        Regex("^my ([a-z' ]{2,40}?) (?:is|are) (.+)$"),
        Regex("^i (?:live|work|study|stay) (?:in|at|for|near) (.+)$"),
        Regex("^i (?:really )?(?:like|love|hate|prefer|enjoy|don't like|do not like|can't stand) (.+)$"),
        Regex("^i(?: am|'m) (?:a|an) (.+)$"),
        Regex("^i(?: am|'m) (?:allergic to|from|vegetarian|vegan|married|single|left handed|right handed)\\b.*$"),
        Regex("^i was born (?:in|on) (.+)$"),
        Regex("^i have (?:a|an|one|two|three|four|five) (.+)$"),
    )

    /** Subjects that change all the time; not worth keeping. */
    private val PASSING_SUBJECT = setOf(
        "phone", "battery", "screen", "internet", "wifi", "data", "net", "head", "stomach", "back", "mood", "day",
        "question", "problem", "plan", "alarm", "timer", "call", "message", "order", "food", "tea", "coffee",
    )

    /** Words that make "I have a …" / "I'm a …" a passing state. */
    private val PASSING_WORDS = setOf(
        "headache", "fever", "cold", "cough", "meeting", "question", "problem", "doubt", "idea", "call", "exam",
        "test", "class", "appointment", "bit", "little", "lot", "moment", "minute", "second", "while",
        "now", "today", "tonight", "tomorrow", "yesterday", "currently",
    )

    /** "I love you", "I hate it": chat, not facts. */
    private val VAGUE_OBJECT = setOf("you", "it", "this", "that", "them", "him", "her", "jimi", "jarvis", "when", "how")

    private val COMMAND_WORDS = setOf("please", "remind", "call", "send", "open", "set", "turn", "play", "show", "tell")

    fun spot(text: String): String? {
        val raw = text.trim().trimEnd('.', '!', ' ')
        if (raw.isEmpty() || raw.contains('?')) return null
        val t = raw.lowercase().replace('’', '\'').replace(Regex("\\s+"), " ")
        if (QUESTION_START.containsMatchIn(t)) return null
        val words = t.split(' ')
        if (words.size < 3 || words.size > MAX_WORDS) return null
        if (words.any { it in COMMAND_WORDS } && !t.startsWith("my ")) return null
        if (words.any { it.trim(',') in PASSING_WORDS }) return null
        val match = PATTERNS.firstNotNullOfOrNull { it.find(t) } ?: return null
        if (t.startsWith("my ")) {
            // Head noun only: "my phone is slow" is passing, "my mom's phone number is …" is kept.
            if (match.groupValues[1].split(' ').last().removeSuffix("'s") in PASSING_SUBJECT) return null
        }
        if (match.groupValues.getOrNull(1)?.substringBefore(' ') in VAGUE_OBJECT) return null
        return raw.replaceFirstChar { it.uppercaseChar() }
    }
}

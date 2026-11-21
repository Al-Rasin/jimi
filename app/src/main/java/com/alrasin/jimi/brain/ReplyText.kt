package com.alrasin.jimi.brain

/** Follow-up noise and plain reply text (ROADMAP §M.3). */
object ReplyText {
    private val FILLER = setOf(
        "ok", "okay", "hmm", "hm", "um", "uh", "ah", "oh", "huh", "so", "yeah", "yes", "no", "right", "well", "fine",
        "alright", "i", "said", "he", "she", "it", "that", "sounds", "good", "the", "a", "and", "is", "was", "mm", "hmmm", "uhh", "umm",
    )
    private val PACKAGE = Regex("\\b(?:com|org|net|io|miui|android|in|me)\\.[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*\\b")

    /**
     * A follow-up turn with no command in it ("okay", "sounds", "I said he"): end the follow-up quietly.
     * Callers check rules, small talk and pending answers first.
     */
    fun isNoise(text: String): Boolean {
        val words = Persona.clean(text).split(' ').filter { it.isNotBlank() }
        // Only known filler words: a lone name ("mom") may answer "Who should I contact?".
        return words.size <= 4 && words.all { it in FILLER }
    }

    private val OTHER_WAKE = Regex("^(?:hey|hi|ok|okay) (?:siri|judy|google|alexa|bixby|cortana|jim)(?: (?:uh|huh|hmm|um|uh huh))*$")
    private val KEEP_REPEATED = setOf("hello", "hey", "help", "stop", "bye", "yes", "no", "jimi", "jimmy")

    /**
     * Spoken stray sound, not a request: filler ("uh huh"), another assistant's wake ("hey siri", "hey judy"),
     * or one word said again ("hoga hoga"). Voice turns only; callers check rules and small talk first.
     */
    fun isStray(text: String): Boolean {
        val t = Persona.clean(text)
        if (isNoise(t) || OTHER_WAKE.matches(t)) return true
        val words = t.split(' ').filter { it.isNotBlank() }
        return words.size in 2..3 && words.distinct().size == 1 && words[0].length >= 3 && words[0] !in KEEP_REPEATED
    }

    /** Replaces package names (com.whatsapp, miui.home) with the app label, or "that app". */
    fun withoutPackages(text: String, label: (String) -> String? = { null }): String =
        PACKAGE.replace(text) { label(it.value)?.takeIf { l -> l.isNotBlank() && !PACKAGE.matches(l) } ?: "that app" }

    /** Plain sentence when routing throws before any tool ran. */
    fun routeFailed(stage: String): String = when {
        stage.startsWith("tool:") -> "Sorry ${Persona.ADDRESS}, ${stage.removePrefix("tool:").replace('_', ' ')} didn't work this time."
        stage in setOf("llm", "chat", "cloud") -> "Sorry ${Persona.ADDRESS}, my thinking failed on that. Please say it again."
        else -> "Sorry ${Persona.ADDRESS}, I didn't get that. Please say it again."
    }
}

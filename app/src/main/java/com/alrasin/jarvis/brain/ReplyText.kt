package com.alrasin.jarvis.brain

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

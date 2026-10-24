package com.alrasin.jarvis.agent

object AgentSafety {
    private val risky = Regex("(?i)pay|buy|send|delete|purchase|checkout|place.?order|confirm|book.?ride|request.?ride|remove|transfer|submit")
    private val secret = Regex("(?i)password|passcode|\\bpin\\b|\\botp\\b|one.?time|verification.?code|security.?code")

    /** Risk for a stored map element from its label text. [password] = field is a password/secret input. */
    fun riskLevel(label: String, password: Boolean = false): String = when {
        password || secret.containsMatchIn(label) -> "secret"
        risky.containsMatchIn(label) -> "high"
        else -> "low"
    }

    fun confirmation(action: AgentAction, snapshot: ScreenSnapshot): String? {
        val index = when (action) { is AgentAction.Tap -> action.element; is AgentAction.Type -> action.element; else -> return null }
        val target = snapshot.elements.firstOrNull { it.index == index } ?: return "The target is unavailable."
        val label = "${target.text} ${target.description} ${target.resourceId}"
        if (action is AgentAction.Type && (target.password || secret.containsMatchIn(label) ||
                    snapshot.elements.any { secret.containsMatchIn("${it.text} ${it.description}") })) {
            return "May I enter private credentials in this field? Please confirm by voice."
        }
        if (action is AgentAction.Tap && (target.safetyUncertain || risky.containsMatchIn(label) ||
                    (target.text.isBlank() && target.description.isBlank()))) {
            return "May I activate #$index? It may send, purchase, or delete something. Please confirm by voice."
        }
        return null
    }
}

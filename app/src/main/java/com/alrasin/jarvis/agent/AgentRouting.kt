package com.alrasin.jarvis.agent

object AgentRouting {
    private val explanation = Regex("^(?:how (?:do|can|would|to)|what|why|when|who|explain|tell me about)\\b", RegexOption.IGNORE_CASE)
    private val command = Regex("\\b(?:turn on|turn off|enable|disable|change|set|switch|book|order|buy|reply|send|delete|tap|click|scroll|type|open|search|find|select|choose|navigate|go to)\\b", RegexOption.IGNORE_CASE)
    private val screenContext = Regex("\\b(?:screen|settings|app|dark mode|light mode|pathao|uber|whatsapp|messenger|telegram|instagram|facebook|youtube|ride|last message|first message|last chat|button|menu|profile|account|checkout)\\b", RegexOption.IGNORE_CASE)
    private val unsupportedScreenTask = Regex(
        "\\b(?:dark mode|light mode|book|order|buy|checkout)\\b|" +
            "\\breply\\b.*\\b(?:last|latest|first|recent|from)\\b|" +
            "\\b(?:change|edit|update)\\b.*\\b(?:profile|account|settings)\\b|" +
            "\\b(?:and then|then)\\b|\\band\\s+(?:play|search|find|tap|click|type|reply|send|select|choose|change|turn|enable|disable)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun needsScreen(text: String): Boolean {
        val request = text.trim().replace(Regex("^(?:please|can you|could you|would you)\\s+", RegexOption.IGNORE_CASE), "")
        return !explanation.containsMatchIn(request) && command.containsMatchIn(request) && screenContext.containsMatchIn(request)
    }

    fun needsMultiStep(text: String): Boolean = needsScreen(text) && unsupportedScreenTask.containsMatchIn(text)

    fun acceptsSingleTool(text: String, tool: String): Boolean = !needsMultiStep(text) || tool !in partialTools

    private val partialTools = setOf("open_app", "toggle", "read_screen", "tap", "type_text", "scroll", "go_back", "go_home", "open_recents", "send_whatsapp", "send_sms", "read_messages")
}

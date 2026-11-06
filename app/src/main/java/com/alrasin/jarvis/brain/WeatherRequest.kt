package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

internal object WeatherRequest {
    /** "at my place / here / near me": no place, so the weather tool uses the current location (§M.8). */
    private val here = Regex(
        " (?:(?:at|in|for|near|around) (?:my (?:place|area|location|current location|home|city)|here|where i am|me)|here|near me|around me|nearby)$",
    )

    fun match(text: String): ToolCall? {
        val day = if (Regex("\\btomorrows?\\b").containsMatchIn(text)) "tomorrow" else "today"
        val withoutDay = text.replace(Regex("\\b(?:todays|today|tomorrows|tomorrow|right now|now|outside|current|currently|latest)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
            .removeSuffix(" right") // normalize() drops the "now" of "right now"
            .replace(here, "")
        val location = Regex(" (?:in|for|at) ([a-z][a-z ]*)$").find(withoutDay)
        val question = if (location == null) withoutDay else withoutDay.substring(0, location.range.first)
        val patterns = listOf(
            "(?:(?:whats|what is|hows|how is|tell me|show me|give me|check|get)(?: me)? )?(?:the )?(?:weather|temperature)" +
                "(?: like| forecast| report| update| updates| conditions?| info)?",
            "(?:will it|is it going to|is it) (?:rain|raining)",
            "(?:do i need|should i take|should i carry) (?:an? |my )?umbrella",
            "how (?:hot|cold) is it",
            "(?:whats|what is) (?:the )?rain (?:chance|forecast)",
        )
        if (patterns.none { Regex(it).matches(question) }) return null
        return ToolCall("weather", buildMap {
            if (day == "tomorrow") put("day", day)
            location?.groupValues?.get(1)?.let { put("place", it) }
        })
    }
}

package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall

internal object WeatherRequest {
    fun match(text: String): ToolCall? {
        val day = if (Regex("\\btomorrow\\b").containsMatchIn(text)) "tomorrow" else "today"
        val withoutDay = text.replace(Regex("\\b(?:today|tomorrow|now|right now|outside)\\b"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val location = Regex(" in ([a-z][a-z ]*)$").find(withoutDay)
        val question = if (location == null) withoutDay else withoutDay.substring(0, location.range.first)
        val patterns = listOf(
            "(?:(?:whats|what is|hows|how is|tell me|show me|check) )?(?:the )?(?:weather|temperature)(?: like| forecast| report)?",
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

package com.alrasin.jarvis.brain

import org.junit.Assert.*
import org.junit.Test

class WeatherFastRouteTest {
    @Test fun weatherRequestsReachTheToolWithoutWebSearch() {
        listOf("what's the weather", "weather forecast", "what is the temperature outside",
            "is it raining", "will it rain tomorrow", "should I carry an umbrella", "how hot is it today").forEach { text ->
            val rule = RuleMatcher.match(text)
            assertEquals(text, "weather", rule?.tool)
            assertEquals(text, "weather", AssistantRouting.webRouteCall(text, rule, IntentRouter.classify(text, rule))?.tool)
        }
    }

    @Test fun cityAndTomorrowArePreserved() {
        val call = RuleMatcher.match("weather in London tomorrow")!!
        assertEquals("london", call.arg("place"))
        assertEquals("tomorrow", call.arg("day"))
    }

    @Test fun unrelatedTemperaturesAreNotLocalWeather() {
        listOf("explain weather", "battery temperature", "open weather app", "what is the temperature of the sun").forEach {
            assertNull(WeatherRequest.match(RuleMatcher.normalize(it)))
        }
    }
}

class WeatherM8PhrasesTest {
    @Test fun herePhrasesUseCurrentLocation() {
        listOf("todays weather update", "current weather", "weather at my place", "whats the weather near me",
            "give me the weather here", "weather conditions right now", "whats the weather right now").forEach { text ->
            val call = RuleMatcher.match(text)
            assertEquals(text, "weather", call?.tool)
            assertNull(text, call?.arg("place"))
        }
    }

    @Test fun namedPlaceKept() {
        assertEquals("chittagong", RuleMatcher.match("weather for chittagong")?.arg("place"))
        assertEquals("tomorrow", RuleMatcher.match("tomorrows weather")?.arg("day"))
    }
}

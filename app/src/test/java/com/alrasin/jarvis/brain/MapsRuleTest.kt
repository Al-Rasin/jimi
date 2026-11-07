package com.alrasin.jarvis.brain

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

/** "navigate to X", "show X on the map" → open_map (ROADMAP §H.2). */
class MapsRuleTest {
    private val previousMatcher = RuleMatcher.appNameMatches

    @Before fun knownApps() {
        RuleMatcher.appNameMatches = { it in setOf("youtube", "whatsapp", "maps", "google maps", "settings") }
    }

    @After fun restore() {
        RuleMatcher.appNameMatches = previousMatcher
    }

    private fun args(text: String): Map<String, String> {
        val c = RuleMatcher.match(text)
        assertEquals(text, "open_map", c?.tool)
        return c!!.args
    }

    @Test fun navigate() {
        assertEquals(mapOf("place" to "gulshan 2", "mode" to "navigate"), args("navigate to Gulshan 2"))
        assertEquals(mapOf("place" to "dhanmondi lake", "mode" to "navigate"), args("Jarvis, get directions to Dhanmondi Lake"))
        assertEquals("navigate", args("how do I get to bashundhara city")["mode"])
    }

    @Test fun travelMode() {
        assertEquals("walk", args("walking directions to the mosque")["travel"])
        assertEquals("mosque", args("walking directions to the mosque")["place"])
        assertEquals("walk", args("walk me to the bus stand")["travel"])
        assertEquals("motorbike", args("directions to uttara by motorbike")["travel"])
        assertEquals("drive", args("drive to the airport")["travel"])
    }

    @Test fun showOnMap() {
        assertEquals(mapOf("place" to "hatirjheel", "mode" to "show"), args("show hatirjheel on the map"))
        assertEquals("pharmacy near me", args("find pharmacy near me on google maps")["place"])
        assertEquals("cox's bazar".replace("'", ""), args("open maps and search coxs bazar")["place"])
    }

    @Test fun screensAndAppsAreNotPlaces() {
        assertNotEquals("open_map", RuleMatcher.match("navigate to wifi settings")?.tool)
        assertNotEquals("open_map", RuleMatcher.match("navigate to youtube")?.tool)
        assertEquals("app_go", RuleMatcher.match("navigate to privacy in whatsapp")?.tool)
    }
}

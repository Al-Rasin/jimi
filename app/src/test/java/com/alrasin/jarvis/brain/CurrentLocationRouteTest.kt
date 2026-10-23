package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.CurrentLocation
import com.alrasin.jarvis.actions.locationData
import com.alrasin.jarvis.actions.locationSay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentLocationRouteTest {
    @Test fun whereAmIReachesTheLocationTool() {
        listOf("where am I", "where am i right now", "what's my location", "what is my current location", "my location").forEach { text ->
            val rule = RuleMatcher.match(text)
            assertEquals(text, "my_location", rule?.tool)
            assertEquals(text, "my_location", Assistant.ruleRouteCall(text, rule, IntentRouter.classify(text, rule))?.tool)
        }
    }

    @Test fun weatherWithoutCityLeavesPlaceEmptyForCurrentLocation() {
        assertNull(RuleMatcher.match("what's the weather")!!.arg("place"))
        assertNull(RuleMatcher.match("will it rain tomorrow")!!.arg("place"))
    }

    @Test fun placeNameIsShortAndSkipsBlanks() {
        assertEquals("Dhanmondi, Dhaka", CurrentLocation.placeName("Dhanmondi", null, " ", "Dhaka", "Dhaka Division"))
        assertEquals("Dhaka", CurrentLocation.placeName(null, "Dhaka", "Dhaka"))
        assertNull(CurrentLocation.placeName(null, ""))
    }

    @Test fun sayAndDataText() {
        val fix = CurrentLocation.Fix(23.7461, 90.3742, 12f, "Dhanmondi, Dhaka")
        assertEquals("You're in Dhanmondi, Dhaka.", locationSay(fix))
        assertTrue(locationData(fix).contains("https://maps.google.com/?q=23.74610,90.37420"))
        assertTrue(locationSay(fix.copy(name = null)).contains("map link"))
    }
}

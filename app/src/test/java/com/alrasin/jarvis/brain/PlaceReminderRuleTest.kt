package com.alrasin.jarvis.brain

import com.alrasin.jarvis.actions.PlaceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class PlaceReminderRuleTest {
    private val now = LocalDateTime.parse("2026-11-12T10:00")

    private fun place(command: String, text: String, place: String, whenArg: String = "arrive") {
        val c = RuleMatcher.match(command, now)!!
        assertEquals(command, "place_reminder", c.tool)
        assertEquals(command, text, c.args["text"])
        assertEquals(command, place, c.args["place"])
        assertEquals(command, whenArg, c.args["when"])
    }

    @Test fun reminderAfterText() {
        place("Remind me to call mom when I reach home", "call mom", "home")
        place("remind me to buy milk when I get to the office", "buy milk", "office")
        place("remind me to lock the door when I leave home", "lock the door", "home", "leave")
        place("remind me to take my bag once I get home", "take my bag", "home")
    }

    @Test fun reminderBeforeText() {
        place("remind me when I reach the office to send the report", "send the report", "office")
        place("when I get home remind me to charge the laptop", "charge the laptop", "home")
    }

    @Test fun savePlace() {
        val c = RuleMatcher.match("this is home", now)!!
        assertEquals("save_place", c.tool)
        assertEquals("home", c.args["name"])
        assertEquals("office", RuleMatcher.match("this is my office", now)!!.args["name"])
        assertEquals("gym", RuleMatcher.match("save this place as gym", now)!!.args["name"])
        assertNotEquals("save_place", RuleMatcher.match("this is good", now)?.tool)
    }

    @Test fun timeReminderStillWorks() {
        assertEquals("create_reminder", RuleMatcher.match("remind me to call mom at 5 pm", now)!!.tool)
    }

    @Test fun placeKeyAliases() {
        assertEquals("home", PlaceText.key("My House"))
        assertEquals("office", PlaceText.key("work"))
        assertEquals("office", PlaceText.key("the office"))
    }

    @Test fun codecRoundTrip() {
        val r = PlaceText.Rem(7, "home", leave = false, armed = true, text = "call mom | dad")
        assertEquals(r, PlaceText.decode(7, PlaceText.encode(r)))
        assertNull(PlaceText.decode(1, "bad"))
        assertEquals(23.8 to 90.4, PlaceText.decodePlace(PlaceText.encodePlace(23.8, 90.4)))
    }
}

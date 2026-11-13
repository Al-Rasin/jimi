package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutinesTextTest {
    @Test fun textRoundTripsDefaults() {
        Routines.DEFAULT_ROUTINES.values.forEach { steps ->
            val back = Routines.parse(Routines.toText(steps))
            assertNull(back.error)
            assertEquals(steps, back.steps)
        }
        assertEquals("toggle target=dnd, state=on\nlist_reminders", Routines.toText(Routines.DEFAULT_ROUTINES.getValue("good_night").take(1) + ToolCall("list_reminders")))
    }

    @Test fun parsesSpacesCommentsAndCommasInValues() {
        val p = Routines.parse("# start\n\n  say text=Good night, sir \nset_volume stream = media , level=3\nweather")
        assertNull(p.error)
        assertEquals(
            listOf(
                ToolCall("say", mapOf("text" to "Good night, sir")),
                ToolCall("set_volume", mapOf("stream" to "media", "level" to "3")),
                ToolCall("weather"),
            ),
            p.steps,
        )
    }

    @Test fun reportsBadLines() {
        assertEquals("No steps", Routines.parse(" \n# only comment").error)
        assertEquals("Line 2: bad tool name \"1abc\"", Routines.parse("weather\n1abc x=1").error)
        assertEquals("Line 1: use key=value", Routines.parse("toggle dnd on").error)
        assertEquals("Line 1: no tool \"fly\"", Routines.parse("fly to=moon") { it != "fly" }.error)
    }

    @Test fun keyFromName() {
        assertEquals("movie_night", Routines.key("  Movie Night! "))
        assertEquals("good_morning", Routines.key("good morning"))
    }
}

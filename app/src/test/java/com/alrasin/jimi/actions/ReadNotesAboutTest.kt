package com.alrasin.jimi.actions

import com.alrasin.jimi.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class ReadNotesAboutTest {
    private fun n(text: String) = Note(timeMs = 0, text = text)

    @Test fun listsShoppingItemsWithoutPrefix() {
        val r = readNotesAbout(listOf(n("shopping list milk"), n("call the plumber"), n("Shopping list eggs")), "shopping list")
        assertEquals("Your shopping list: milk and eggs.", r.say)
    }

    @Test fun saysWhenNothingMatches() {
        assertEquals("I have no notes about shopping list.", readNotesAbout(listOf(n("call the plumber")), "shopping list").say)
    }
}

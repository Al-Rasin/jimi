package com.alrasin.jimi.actions

import com.alrasin.jimi.data.Note
import org.junit.Assert.assertEquals
import org.junit.Test

class ListItemIdsTest {
    private val notes = listOf(
        Note(1, 0, "shopping list milk"), Note(2, 0, "Shopping list: Milk"),
        Note(3, 0, "shopping list eggs"), Note(4, 0, "buy milk for rahim"),
    )

    @Test fun findsOnlyThatItem() {
        assertEquals(listOf(1L, 2L), listItemIds(notes, "shopping list", "milk"))
        assertEquals(emptyList<Long>(), listItemIds(notes, "shopping list", "bread"))
    }
}

package com.alrasin.jarvis.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTest {
    private val bar = ScreenOcr.Box("Home Settings Profile", 0, 2000, 1080, 2080)
    private val words = listOf(
        ScreenOcr.Box("Home", 40, 2000, 200, 2080, word = true),
        ScreenOcr.Box("Settings", 440, 2000, 640, 2080, word = true),
        ScreenOcr.Box("Profile", 880, 2000, 1040, 2080, word = true),
    )

    @Test fun findsWordInsideLineAndTapsItsCentre() {
        val items = ScreenOcr.items(listOf(bar) + words)
        val pick = Skills.pickNamed(items, "settings")
        val item = (pick as Skills.Pick.Found).item
        assertNull(item.node)
        assertEquals(540 to 2040, ScreenOcr.centre(item))
    }

    @Test fun textIsInReadingOrderAndSkipsWords() {
        val boxes = listOf(
            ScreenOcr.Box("Second line", 10, 300, 400, 340),
            ScreenOcr.Box("right", 600, 102, 700, 140),
            ScreenOcr.Box("Title", 10, 100, 300, 140),
        ) + words
        assertEquals("Title\nright\nSecond line", ScreenOcr.text(boxes))
    }

    @Test fun dropsBlankAndEmptyBoxes() {
        val items = ScreenOcr.items(listOf(ScreenOcr.Box(" ", 0, 0, 10, 10), ScreenOcr.Box("x", 5, 5, 5, 9)))
        assertTrue(items.isEmpty())
    }

    @Test fun thinTree() {
        assertTrue(ScreenOcr.treeIsThin(listOf(Skills.Item(text = "A"))))
        assertFalse(ScreenOcr.treeIsThin(listOf("A", "B", "C").map { Skills.Item(text = it) }))
    }
}

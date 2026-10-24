package com.alrasin.jarvis.agent

import com.alrasin.jarvis.agent.Skills.Item
import com.alrasin.jarvis.agent.Skills.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure picking rules for the general app skills (ROADMAP §H.4). */
class SkillsTest {
    private fun row(text: String, top: Int, clickable: Boolean = true, left: Int = 0, right: Int = 1080, h: Int = 100) =
        Item(text = text, clickable = clickable, left = left, top = top, right = right, bottom = top + h)

    private val searchBox = Item(hint = "Search", editable = true, top = 100, bottom = 200, right = 1080)

    @Test fun rowScoreCoversLongRows() {
        assertTrue(Skills.rowScore("lofi beats", "Lofi Beats to study\n2M views") >= Skills.MIN)
        assertTrue(Skills.rowScore("settings", "Settings") == 1.0)
        assertTrue(Skills.rowScore("music", "Photos") < Skills.MIN)
        assertEquals(0.0, Skills.rowScore("", "x"), 0.0)
    }

    @Test fun resultBelowSearchField() {
        val items = listOf(searchBox, row("Lofi girl", 50), row("Lofi girl radio", 300), row("Jazz cafe", 400))
        val pick = Skills.pickResult(items, "lofi girl radio", belowY = 200)
        assertEquals("Lofi girl radio", (pick as Pick.Found).item.text)
    }

    @Test fun twoCloseResultsAsk() {
        val items = listOf(row("Rahim Uddin", 300), row("Rahim Udin", 400))
        val pick = Skills.pickResult(items, "rahim", belowY = 0)
        assertTrue(pick is Pick.Ambiguous)
        assertEquals(2, (pick as Pick.Ambiguous).options.size)
    }

    @Test fun sameLabelTwiceIsNotAmbiguous() {
        val items = listOf(row("News", 300), row("News", 400))
        assertEquals(300, ((Skills.pickResult(items, "news", 0)) as Pick.Found).item.top)
    }

    @Test fun nothingCloseIsNotFound() {
        assertEquals(Pick.NotFound, Skills.pickResult(listOf(row("Jazz cafe", 300)), "cricket score", 0))
    }

    @Test fun firstResultIsTopRowUnderField() {
        val items = listOf(searchBox, row("Top hit", 300), row("Second", 400))
        assertEquals("Top hit", Skills.firstResult(items, 200, 2000)?.text)
    }

    @Test fun namedPrefersClickable() {
        val items = listOf(row("Storage", 300, clickable = false), row("Storage", 500))
        assertTrue(((Skills.pickNamed(items, "storage")) as Pick.Found).item.clickable)
        assertEquals("About", ((Skills.pickNamed(listOf(row("About", 300, clickable = false)), "about")) as Pick.Found).item.text)
    }

    @Test fun tabInBottomBarWins() {
        val items = listOf(row("Calls", 700), row("Calls", 1900), row("Chats", 1900))
        val pick = Skills.pickTab(items, "calls", screenH = 2000)
        assertEquals(1900, (pick as Pick.Found).item.top)
        assertEquals(Pick.NotFound, Skills.pickTab(listOf(row("Library", 900)), "library", 2000))
        assertEquals(100, (Skills.pickTab(listOf(row("Library", 100)), "library", 2000) as Pick.Found).item.top)
    }

    @Test fun searchFieldThenButton() {
        assertEquals(searchBox, Skills.pickSearch(listOf(row("Home", 0), searchBox)))
        val button = Item(desc = "Search", clickable = true, top = 50, bottom = 100)
        assertEquals(button, Skills.pickSearch(listOf(row("Home", 0), button)))
        val bangla = Item(desc = "খুঁজুন", clickable = true, top = 50, bottom = 100)
        assertEquals(bangla, Skills.pickSearch(listOf(bangla)))
        assertNull(Skills.pickSearch(listOf(row("Home", 0))))
    }

    @Test fun toggleNextToLabel() {
        val label = row("Dark theme", 300, clickable = false, right = 800)
        val near = Item(checkable = true, clickable = true, left = 900, top = 310, right = 1000, bottom = 380)
        val other = Item(checkable = true, clickable = true, left = 900, top = 600, right = 1000, bottom = 680)
        val pick = Skills.pickToggle(listOf(label, near, other), "dark theme")
        val found = (pick as Pick.Found).item
        assertEquals(310, found.top)
        assertEquals("Dark theme", found.label)
    }

    @Test fun toggleWithOwnLabel() {
        val sw = Item(text = "Wi-Fi calling", checkable = true, checked = true, clickable = true, top = 300, bottom = 400)
        assertTrue(((Skills.pickToggle(listOf(sw), "wifi calling")) as Pick.Found).item.checked)
    }

    @Test fun rowsSkipBarsAndNested() {
        val outer = row("Inbox\nHello", 300, h = 200)
        val inner = row("Hello", 350, left = 100, right = 500, h = 50)
        val items = listOf(row("Chats", 1900), outer, inner, row("Promo", 600), searchBox.copy(top = 300))
        val rows = Skills.rows(items, 2000)
        assertEquals(listOf("Inbox\nHello", "Promo"), rows.map { it.text })
        assertEquals("1, Inbox, Hello. 2, Promo.", Skills.formatRows(rows))
    }

    @Test fun riskyWords() {
        for (w in listOf("Send", "Pay now", "Delete chat", "Place order", "Confirm", "Uninstall", "Log out", "পাঠান")) {
            assertTrue(w, Skills.isRisky(w))
        }
        for (w in listOf("Calls", "Display", "Storage", "Following", "Posts", "Library", "Search")) {
            assertFalse(w, Skills.isRisky(w))
        }
    }
}

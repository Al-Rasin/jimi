package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.UiQuery.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHealTest {
    private fun c(text: String, id: String = "", desc: String = "") = Candidate(resourceId = id, text = text, desc = desc, clickable = true)

    @Test fun opposites() {
        assertTrue(SelfHeal.opposite("Log in", "Log out"))
        assertTrue(SelfHeal.opposite("Pay", "Pay later"))
        assertTrue(SelfHeal.opposite("Follow", "Unfollow"))
        assertTrue(SelfHeal.opposite("Mute", "Unmute"))
        assertFalse(SelfHeal.opposite("Settings", "Settings"))
        assertFalse(SelfHeal.opposite("Log in", "Log in now"))
        assertFalse(SelfHeal.opposite("Profile", "Edit profile"))
    }

    @Test fun clearWinnerHeals() {
        val s = SelfHeal.Saved("Settings", resourceId = "app:id/old_settings")
        val win = SelfHeal.pick(s, listOf(c("Settings", id = "app:id/new_settings"), c("Help"), c("About")))
        assertEquals("Settings", win?.text)
    }

    @Test fun sameIdNewTextHeals() {
        val s = SelfHeal.Saved("Settings", resourceId = "app:id/settings")
        val win = SelfHeal.pick(s, listOf(c("Preferences", id = "app:id/settings"), c("Help")))
        assertEquals("app:id/settings", win?.resourceId)
    }

    @Test fun ambiguousGivesNull() {
        val s = SelfHeal.Saved("Settings")
        assertNull(SelfHeal.pick(s, listOf(c("Settings", id = "a"), c("Settings", id = "b"))))
    }

    @Test fun oppositeNeverHeals() {
        assertNull(SelfHeal.pick(SelfHeal.Saved("Log in"), listOf(c("Log out"))))
        assertNull(SelfHeal.pick(SelfHeal.Saved("Follow"), listOf(c("Unfollow"))))
    }

    @Test fun riskierNeverHeals() {
        assertNull(SelfHeal.pick(SelfHeal.Saved("Draft"), listOf(c("Delete draft"))))
    }

    @Test fun noMatch() {
        assertNull(SelfHeal.pick(SelfHeal.Saved("Settings"), listOf(c("Help"), c("About"))))
        assertNull(SelfHeal.pick(SelfHeal.Saved("Settings"), emptyList()))
    }

    @Test fun stale() {
        val e = AppEdge(fromScreen = 1, element = 2, toScreen = 3, timesSeen = 1, lastOk = 0, failedCount = 1)
        assertFalse(SelfHeal.isStale(e))
        assertTrue(SelfHeal.isStale(e.copy(failedCount = 2)))
    }

    @Test fun freshness() {
        assertEquals(SelfHeal.Fresh.SAME, SelfHeal.freshness(5, 5))
        assertEquals(SelfHeal.Fresh.CHECK_AGAIN, SelfHeal.freshness(5, 6))
        assertEquals(SelfHeal.Fresh.DELETE, SelfHeal.freshness(5, null))
        assertEquals(SelfHeal.Fresh.SAME, SelfHeal.freshness(0, 9))
    }

    @Test fun healedSelectorsOrder() {
        val sel = SelfHeal.healedSelectors(Candidate(resourceId = "x:id/a", desc = "Open menu", text = "Menu"))
        assertEquals(listOf("id", "desc", "text"), sel.map { it["type"] })
    }
}

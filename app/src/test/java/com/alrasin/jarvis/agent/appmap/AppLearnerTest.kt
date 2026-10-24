package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.appmap.ScreenStructure.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLearnerTest {

    private fun tab(label: String) = RawNode(desc = label, clickable = true, parentClass = "com.google.android.material.bottomnavigation.BottomNavigationView")

    @Test fun listRowsAreNeverSaved() {
        val row = RawNode(text = "Rahim", clickable = true, inList = true)
        assertNull(ScreenStructure.element(row))
    }

    @Test fun typedTextAndPasswordsAreNeverSaved() {
        val field = RawNode(text = "my secret message", hint = "Message", editable = true, clickable = true)
        val e = ScreenStructure.element(field)!!
        assertEquals("message", e.label)
        assertEquals("field", e.role)

        val pw = RawNode(text = "hunter2", hint = "", resourceId = "app:id/password", editable = true, password = true)
        val p = ScreenStructure.element(pw)!!
        assertEquals("password", p.label)
        assertEquals("secret", p.risk)
    }

    @Test fun nonClickableTextIsIgnored() {
        assertNull(ScreenStructure.element(RawNode(text = "Hello from Karim")))
    }

    @Test fun longLabelsAreDropped() {
        assertNull(ScreenStructure.element(RawNode(text = "This is a long post someone wrote today", clickable = true)))
    }

    @Test fun badgeCountsDoNotChangeLabel() {
        assertEquals(ScreenStructure.element(tab("Chats, 3 unread")), ScreenStructure.element(tab("Chats, 5 unread")))
    }

    @Test fun rolesAreGuessed() {
        assertEquals("tab", ScreenStructure.role(tab("Chats")))
        assertEquals("switch", ScreenStructure.role(RawNode(className = "android.widget.Switch", clickable = true)))
        assertEquals("menu", ScreenStructure.role(RawNode(desc = "More options", clickable = true)))
        assertEquals("drawer", ScreenStructure.role(RawNode(resourceId = "x:id/nav_view_item", clickable = true)))
        assertEquals("button", ScreenStructure.role(RawNode(text = "New group", clickable = true)))
    }

    @Test fun riskyButtonsAreMarkedHigh() {
        assertEquals("high", ScreenStructure.element(RawNode(text = "Send money", clickable = true))!!.risk)
        assertEquals("low", ScreenStructure.element(RawNode(text = "Settings", clickable = true))!!.risk)
    }

    @Test fun banglaLabelsKeepTheirMarks() {
        assertEquals("সেটিংস", ScreenStructure.clean("সেটিংস"))
    }

    @Test fun fingerprintStableWhenListContentChanges() {
        val base = listOf(tab("Chats"), tab("Updates"), tab("Calls"))
        val a = ScreenStructure.elements(base + RawNode(text = "Rahim", clickable = true, inList = true))
        val b = ScreenStructure.elements(base + RawNode(text = "Karim", clickable = true, inList = true))
        assertEquals(AppFingerprint.compute("Home", ScreenStructure.parts(a)), AppFingerprint.compute("Home", ScreenStructure.parts(b)))
    }

    @Test fun skipsJimiKeyboardSystemUiPaymentAndLocked() {
        assertFalse(AppLearner.shouldWatch("com.alrasin.jarvis", "com.alrasin.jarvis", null, false))
        assertFalse(AppLearner.shouldWatch("com.kb", "own", "com.kb", false))
        assertFalse(AppLearner.shouldWatch("com.android.systemui", "own", null, false))
        assertFalse(AppLearner.shouldWatch("com.bKash.customerapp", "own", null, false))
        assertFalse(AppLearner.shouldWatch("com.whatsapp", "own", null, locked = true))
        assertTrue(AppLearner.shouldWatch("com.whatsapp", "own", null, false))
    }

    @Test fun recentScreensSkipsWithinTenSeconds() {
        val r = RecentScreens()
        r.put("p", "fp", 7, now = 1_000)
        assertEquals(7L, r.idIfFresh("p", "fp", now = 9_000))
        assertNull(r.idIfFresh("p", "fp", now = 11_000))
        assertNull(r.idIfFresh("q", "fp", now = 2_000))
    }

    @Test fun selectorsPutIdFirst() {
        val e = ScreenStructure.Element("chats", "tab", "com.whatsapp:id/tab", "", "low")
        val json = AppLearner.selectorsJson(e)
        assertTrue(json.indexOf("\"id\"") < json.indexOf("\"desc\""))
    }
}

package com.alrasin.jarvis.agent.appmap

import com.alrasin.jarvis.agent.appmap.ScreenStructure.RawNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What "learn this app" may tap, and when it must stop (ROADMAP §J.5). */
class ExplorePolicyTest {
    private fun tab(text: String) = RawNode(text = text, className = "android.widget.FrameLayout",
        parentClass = "com.google.android.material.bottomnavigation.BottomNavigationView", clickable = true)
    private fun button(text: String = "", desc: String = "", id: String = "") =
        RawNode(resourceId = id, text = text, desc = desc, className = "android.widget.Button", clickable = true)
    private fun row(text: String) = RawNode(text = text, className = "android.widget.TextView", inList = true, clickable = true)

    @Test fun safeTargetsInRoleOrder() {
        val nodes = listOf(
            button(text = "Settings"),
            button(desc = "More options"),
            tab("Chats"),
            tab("Calls 3"),
            button(text = "Send"),
            button(text = "Start"),
            RawNode(text = "Wi-Fi", className = "android.widget.Switch", clickable = true, checkable = true),
            RawNode(hint = "Search", className = "android.widget.EditText", clickable = true, editable = true),
        )
        val plan = ExplorePolicy.plan(nodes, "com.app.MainActivity")
        assertEquals(listOf("tab", "tab", "menu", "button"), plan.targets.map { it.element.role })
        assertEquals(listOf("chats", "calls", "more options", "settings"), plan.targets.map { it.element.label })
        assertTrue(plan.risky.any { it.label == "send" && it.risk == "high" })
    }

    @Test fun profileButtonIsSafe() {
        // Mi Remote's settings live behind its "my" button.
        val plan = ExplorePolicy.plan(listOf(button(desc = "my"), button(desc = "add")), "com.app.MainActivity")
        assertEquals(listOf("my"), plan.targets.map { it.element.label })
    }

    @Test fun plainSettingsLabelIsATarget() {
        val nodes = listOf(
            RawNode(className = "android.widget.RelativeLayout", clickable = true),
            RawNode(text = "Settings", className = "android.widget.TextView"),
            RawNode(text = "Not signed in", className = "android.widget.TextView"),
        )
        assertEquals(listOf("settings"), ExplorePolicy.plan(nodes, "com.app.UserActivity").targets.map { it.element.label })
    }

    @Test fun drawerButtonIsADrawer() {
        val plan = ExplorePolicy.plan(listOf(button(desc = "Open navigation drawer")), "com.app.MainActivity")
        assertEquals("drawer", plan.targets.single().element.role)
    }

    @Test fun rowsOnlyOnSettingsOrMenuScreens() {
        val rows = listOf(row("Privacy"), row("Notifications"), row("Log out"), row("Delete account"))
        assertTrue(ExplorePolicy.plan(rows, "com.app.MainActivity").targets.isEmpty())
        val settings = ExplorePolicy.plan(rows, "com.app.SettingsActivity")
        assertEquals(listOf("privacy", "notifications"), settings.targets.map { it.element.label })
        assertEquals(setOf("log out", "delete account"), settings.risky.map { it.label }.toSet())
        assertEquals("Privacy", settings.targets.first().raw)
    }

    @Test fun chatAppsNeverOpenRows() {
        val rows = listOf(row("Privacy"), row("Notifications"))
        assertTrue(ExplorePolicy.plan(rows, "com.app.SettingsActivity", "com.whatsapp").targets.isEmpty())
        assertTrue(ExplorePolicy.plan(rows, "com.app.SettingsActivity", "com.facebook.orca").targets.isEmpty())
        assertEquals(2, ExplorePolicy.plan(rows, "com.app.SettingsActivity", "com.app").targets.size)
    }

    @Test fun riskyWords() {
        listOf("Pay now", "Buy", "Send", "Log out", "Sign out", "Delete", "Allow", "OK", "Install", "Uninstall", "Share")
            .forEach { assertTrue(it, ExplorePolicy.risky(it)) }
        listOf("Chats", "Privacy", "Settings", "More options", "Help").forEach { assertFalse(it, ExplorePolicy.risky(it)) }
    }

    @Test fun skippedPackages() {
        assertTrue(ExplorePolicy.skipPackage("com.alrasin.jarvis", "com.alrasin.jarvis"))
        assertTrue(ExplorePolicy.skipPackage("", "com.alrasin.jarvis"))
        assertTrue(ExplorePolicy.skipPackage("com.google.android.dialer", "com.alrasin.jarvis"))
        assertTrue(ExplorePolicy.skipPackage("com.android.camera", "com.alrasin.jarvis"))
        assertTrue(ExplorePolicy.skipPackage("com.miui.home", "com.alrasin.jarvis"))
        assertTrue(ExplorePolicy.skipPackage("com.google.android.apps.nbu.paisa.user", "com.alrasin.jarvis"))
        assertFalse(ExplorePolicy.skipPackage("com.duokan.phone.remotecontroller", "com.alrasin.jarvis"))
    }

    @Test fun stopScreens() {
        assertEquals("secure", ExplorePolicy.stopReason("com.app", emptyList()))
        assertEquals("permission", ExplorePolicy.stopReason("com.android.permissioncontroller", listOf(button(text = "Allow"))))
        assertEquals("login", ExplorePolicy.stopReason("com.app", listOf(RawNode(password = true, editable = true))))
        assertEquals("login", ExplorePolicy.stopReason("com.app",
            listOf(RawNode(hint = "Phone number", editable = true), button(text = "Next"))))
        assertNull(ExplorePolicy.stopReason("com.app", listOf(RawNode(hint = "Search", editable = true), tab("Home"))))
    }
}

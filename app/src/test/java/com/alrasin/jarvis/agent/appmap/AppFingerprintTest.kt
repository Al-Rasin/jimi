package com.alrasin.jarvis.agent.appmap

import org.junit.Assert.*
import org.junit.Test

class AppFingerprintTest {

    @Test fun sameFingerprint_whenOnlyListContentChanges() {
        val visit1 = AppFingerprint.compute("MainActivity", listOf("toolbar", "tab_home", "tab_chats", "fab_compose"))
        val visit2 = AppFingerprint.compute("MainActivity", listOf("toolbar", "tab_home", "tab_chats", "fab_compose"))
        assertEquals(visit1, visit2)
        assertTrue(AppFingerprint.sameScreen(visit1, visit2))
    }

    @Test fun fingerprintChanges_whenTabsChange() {
        val homeTab = AppFingerprint.compute("MainActivity", listOf("toolbar", "tab_home", "tab_chats", "fab_compose"))
        val settingsTab = AppFingerprint.compute("SettingsActivity", listOf("toolbar", "pref_account", "pref_notifications"))
        assertNotEquals(homeTab, settingsTab)
        assertFalse(AppFingerprint.sameScreen(homeTab, settingsTab))
    }

    @Test fun sameScreen_toleratesMinorStructuralDrift() {
        val a = AppFingerprint.compute("MainActivity", listOf("toolbar", "tab_home", "tab_chats", "tab_status", "fab_compose"))
        val b = AppFingerprint.compute("MainActivity", listOf("toolbar", "tab_home", "tab_chats", "tab_status", "banner_ad"))
        assertTrue(AppFingerprint.sameScreen(a, b))
    }

    @Test fun differentWindowClass_isNeverSameScreen() {
        val a = AppFingerprint.compute("MainActivity", listOf("toolbar"))
        val b = AppFingerprint.compute("ChatActivity", listOf("toolbar"))
        assertFalse(AppFingerprint.sameScreen(a, b))
    }
}

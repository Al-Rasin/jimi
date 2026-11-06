package com.alrasin.jarvis.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPagesTest {
    private val pages = listOf(
        SettingsPages.page("a.DISPLAY", "ACTION_DISPLAY_SETTINGS"),
        SettingsPages.page("a.NFC", "ACTION_NFC_SETTINGS"),
        SettingsPages.page("a.NFC_PAY", "ACTION_NFC_PAYMENT_SETTINGS"),
        SettingsPages.page("a.LOCALE", "ACTION_LOCALE_SETTINGS"),
        SettingsPages.page("a.DEV", "ACTION_APPLICATION_DEVELOPMENT_SETTINGS"),
    )

    private fun best(q: String) = SettingsPages.best(q, pages)?.action

    @Test fun androidNameBecomesWords() = assertEquals("nfc payment", pages[2].base)
    @Test fun exactNameWins() = assertEquals("a.NFC", best("nfc settings"))
    @Test fun aliasFindsPage() {
        assertEquals("a.DISPLAY", best("dark mode"))
        assertEquals("a.LOCALE", best("language"))
        assertEquals("a.DEV", best("developer options"))
    }
    @Test fun unknownGoesToSearch() = assertNull(best("reading mode"))

    @Test fun searchTriesSimplerWords() {
        assertEquals(listOf("haptic feedback", "feedback", "haptic"), SettingsPages.queries("Haptic feedback"))
        assertEquals(listOf("vibration mode", "vibrate mode", "vibrate", "mode"), SettingsPages.queries("vibration mode"))
        assertEquals(listOf("wifi calling", "wi-fi calling", "calling", "wi-fi"), SettingsPages.queries("wifi calling"))
    }

    @Test fun resultSharesMostWords() {
        val screen = listOf("Search settings", "Vibration & haptic strength", "Additional settings/Accessibility/Physical", "Cancel")
        assertEquals("Vibration & haptic strength", SettingsPages.match("haptic feedback", screen))
        assertEquals("Reading mode", SettingsPages.match("reading mode", listOf("Mode", "Reading mode", "Display/Reading mode")))
        assertEquals("Wi-Fi calling", SettingsPages.match("wifi calling", listOf("Wi-Fi", "Wi-Fi calling")))
        assertNull(SettingsPages.match("nfc", screen))
    }

    @Test fun spokenNameFindsStoredKey() {
        val e = listOf(
            Triple("system", "haptic_feedback_enabled", "0"), Triple("system", "haptic_feedback_intensity", "2"),
            Triple("system", "dtmf_tone", "1"), Triple("global", "adb_enabled", "1"),
            Triple("secure", "charging_sounds_enabled", "0"), Triple("system", "lockscreen_sounds_enabled", "1"),
        )
        assertEquals("haptic_feedback_enabled", SettingsPages.key("haptic feedback", e)?.second)
        assertEquals("charging_sounds_enabled", SettingsPages.key("charging sounds", e)?.second)
        assertNull(SettingsPages.key("adb", e))
        assertNull(SettingsPages.key("lockscreen sounds", e))
        assertNull(SettingsPages.key("reading mode", e))
    }

    @Test fun hapticUsesItsKey() {
        assertEquals(listOf("system" to "haptic_feedback_enabled"), SettingsPages.known("haptic feedback")?.keys)
        assertEquals("Haptic feedback", SettingsPages.known("touch vibration")?.label)
        assertEquals("Haptic feedback", SettingsPages.known("haptics")?.label)
    }

    @Test fun vibrationWritesMiuiCopies() {
        val k = SettingsPages.known("vibration")!!
        assertEquals("system" to "vibrate_when_ringing", k.keys.first())
        assertTrue(("system" to "vibrate_in_silent") in k.keys)
        assertEquals("Vibration", SettingsPages.known("vibrate mode")?.label)
    }

    @Test fun readingModeKeyIsFallbackAfterSwitch() {
        val k = SettingsPages.known("reading mode")!!
        assertEquals(listOf("system" to "screen_paper_mode_enabled"), k.keys)
        assertTrue(k.appFirst)
        assertEquals("Reading mode", SettingsPages.known("eye comfort mode")?.label)
    }

    @Test fun unknownNamesFallBackToSearch() {
        assertNull(SettingsPages.known("nfc"))
        assertNull(SettingsPages.known("dark mode"))
    }

    @Test fun lightModeMeansDarkOff() {
        assertFalse(SettingsPages.darkWanted("light mode", true))
        assertTrue(SettingsPages.darkWanted("light mode", false))
        assertTrue(SettingsPages.darkWanted("dark mode", true))
    }
}

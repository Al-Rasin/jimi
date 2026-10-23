package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.RuleMatcher
import com.alrasin.jarvis.brain.ToolShortlist
import com.alrasin.jarvis.core.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProfileTest {
    private val profile = PhoneProfile.Profile(
        scannedMs = 1_700_000_000_000, device = "Xiaomi Redmi 10 Prime", android = "Android 12, MIUI V130",
        apps = listOf("Chrome", "Messenger", "Spotify", "WhatsApp"),
        roles = mapOf("messaging" to listOf("WhatsApp", "Messenger"), "music" to listOf("Spotify")),
        contacts = 320, whatsappContacts = 140, sims = 2, features = listOf("nfc", "flashlight"),
    )

    @Test fun jsonRoundTrip() {
        assertEquals(profile, PhoneProfile.fromJson(PhoneProfile.toJson(profile)))
    }

    @Test fun rolesFromPackages() {
        val roles = PhoneProfile.roles(listOf(
            AppList.App("WhatsApp", "com.whatsapp"), AppList.App("Spotify", "com.spotify.music"), AppList.App("Notes", "com.miui.notes"),
        ))
        assertEquals(listOf("WhatsApp"), roles["messaging"])
        assertEquals(listOf("Spotify"), roles["music"])
        assertFalse(roles.values.flatten().contains("Notes"))
    }

    @Test fun cloudLineHasAppsButNoContactNumbers() {
        val line = PhoneProfile.cloudLine(profile)
        assertTrue(line.contains("Spotify") && line.contains("messaging apps: WhatsApp, Messenger"))
        assertFalse(line.contains("320") || line.contains("140"))
    }

    @Test fun spokenSummary() {
        assertEquals(
            "This is a Xiaomi Redmi 10 Prime on Android 12, MIUI V130. I know 4 apps and 320 contacts, 140 of them on WhatsApp. " +
                "You message with WhatsApp and Messenger. Music: Spotify.",
            PhoneProfile.say(profile),
        )
    }

    @Test fun rules() {
        assertEquals(ToolCall("phone_profile", mapOf("scan" to "true")), RuleMatcher.match("scan my phone"))
        assertEquals(ToolCall("phone_profile"), RuleMatcher.match("what do you know about my phone"))
    }

    @Test fun appWordsHintOpenApp() {
        assertEquals(setOf("spotify", "whatsapp", "chrome", "messenger"), ToolShortlist.appWords(profile.apps))
    }

    @Test fun androidAbilitiesMergeWithHints() {
        val merged = PhoneProfile.merge(
            mapOf("messaging" to listOf("Messages", "WhatsApp"), "notes" to listOf("Keep")),
            mapOf("messaging" to listOf("WhatsApp", "Messenger"), "payments" to listOf("bKash"), "video" to emptyList()),
        )
        assertEquals(listOf("Messages", "WhatsApp", "Messenger"), merged["messaging"])
        assertEquals(listOf("Keep"), merged["notes"])
        assertEquals(listOf("bKash"), merged["payments"])
        assertFalse("video" in merged)
    }
}

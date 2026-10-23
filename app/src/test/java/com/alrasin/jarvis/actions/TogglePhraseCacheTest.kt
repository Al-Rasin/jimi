package com.alrasin.jarvis.actions

import com.alrasin.jarvis.voice.PhraseCache
import org.junit.Assert.assertTrue
import org.junit.Test

class TogglePhraseCacheTest {
    @Test fun confirmedToggleRepliesMatchTheStartupAudioCache() {
        listOf("Flashlight", "Wi-Fi", "Bluetooth", "Mobile data", "Do not disturb",
            "Airplane mode", "Location", "Battery saver", "Auto rotate").forEach { label ->
            listOf(true, false).forEach { on ->
                val reply = toggleTruth(label, on, on).say
                assertTrue(reply, reply in PhraseCache.COMMON)
            }
        }
    }
}

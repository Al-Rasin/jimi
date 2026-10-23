package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {
    @Test fun pickerHasFourGoogleVoicesAndAlanBackup() {
        assertEquals(4, Voices.PHONE.size)
        assertTrue(Voices.PHONE.all { it.pack.engine == Engine.ANDROID })
        assertEquals(2, Voices.PHONE.count { it.male })
        assertEquals(Voices.PHONE + Voices.byId(Voices.BUILT_IN), Voices.ALL)
        assertEquals(Voices.ALL.size, Voices.ALL.map { it.id }.distinct().size)
    }

    @Test fun everyPickerVoiceResolves() {
        Voices.ALL.forEach { voice -> assertEquals(voice, Voices.byId(voice.id)) }
    }

    @Test fun builtInAlanRemainsAvailable() {
        val fallback = Voices.byId(Voices.BUILT_IN)
        assertEquals("piper-alan", fallback.id)
        assertEquals(Engine.PIPER, fallback.pack.engine)
        assertTrue(fallback.pack.files.contains("en_GB-alan-medium.onnx"))
    }

    @Test fun absentUnknownOrRemovedVoicesBecomeTheDefault() {
        val removed = (1..5).flatMap { listOf("supertonic-f$it", "supertonic-m$it") } +
            listOf("piper-amy", "piper-ryan", "piper-joe", "google:en-us-x-sfg-local", "google:en-gb-x-gba-local")
        (listOf(null, "", "unknown-voice") + removed).forEach { id ->
            assertEquals("voice $id", Voices.DEFAULT, Voices.byId(id).id)
        }
    }
}

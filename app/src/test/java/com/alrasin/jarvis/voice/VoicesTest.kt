package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoicesTest {
    @Test fun pickerHasThreeMaleAndThreeFemaleVoices() {
        assertEquals(6, Voices.ALL.size)
        assertEquals(3, Voices.ALL.count { it.male })
        assertEquals(3, Voices.ALL.count { !it.male })
        assertEquals(Voices.ALL.size, Voices.ALL.map { it.id }.distinct().size)
        assertEquals(Voices.ALL.size, Voices.ALL.map { it.pack.id to it.sid }.distinct().size)
    }

    @Test fun everyPickerVoiceResolvesAndHasAValidSpeaker() {
        Voices.ALL.forEach { voice ->
            assertEquals(voice, Voices.byId(voice.id))
            assertTrue(voice.sid >= 0)
            if (voice.pack.engine == Engine.SUPERTONIC) assertTrue(voice.sid in 0..9)
        }
    }

    @Test fun builtInAlanRemainsAvailableEvenWhenHiddenFromPicker() {
        val fallback = Voices.byId(Voices.BUILT_IN)
        assertEquals("piper-alan", fallback.id)
        assertEquals(Engine.PIPER, fallback.pack.engine)
        assertEquals(0, fallback.sid)
        assertTrue(fallback.pack.files.contains("en_GB-alan-medium.onnx"))
    }

    @Test fun absentOrUnknownSavedVoiceFallsBackToBuiltIn() {
        listOf(null, "", "unknown-voice").forEach { id ->
            assertEquals(Voices.BUILT_IN, Voices.byId(id).id)
        }
    }

    @Test fun removedLegacyVoiceIdsFallBackWithoutThrowing() {
        val originalIds = (1..5).flatMap { listOf("supertonic-f$it", "supertonic-m$it") } + listOf(
            "piper-alan", "piper-ryan", "piper-joe", "piper-hfc_male", "piper-northern_english_male",
            "piper-amy", "piper-kristin", "piper-hfc_female", "piper-jenny_dioco", "piper-cori",
        )
        val removedIds = originalIds.filter { id -> id != Voices.BUILT_IN && Voices.ALL.none { it.id == id } }
        assertTrue(removedIds.isNotEmpty())
        removedIds.forEach { id -> assertEquals("Removed voice $id", Voices.BUILT_IN, Voices.byId(id).id) }
    }
}

package com.alrasin.jarvis.data

import com.alrasin.jarvis.voice.Voices
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDefaultsTest {
    @Test fun noSavedChoiceUsesTheDefaultVoiceAtNormalSpeed() {
        assertEquals(Voices.DEFAULT, VoiceDefaults.voice(null))
        assertEquals(1.0f, VoiceDefaults.speed(null), 0f)
    }

    @Test fun savedChoicesArePreserved() {
        assertEquals("piper-alan", VoiceDefaults.voice("piper-alan"))
        assertEquals(0.9f, VoiceDefaults.speed(0.9f), 0f)
    }
}

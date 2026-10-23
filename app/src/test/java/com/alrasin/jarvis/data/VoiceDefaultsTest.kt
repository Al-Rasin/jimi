package com.alrasin.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDefaultsTest {
    @Test fun newInstallUsesAmyAtOnePointOne() {
        assertEquals("piper-amy", VoiceDefaults.voice(null, false))
        assertEquals(1.10f, VoiceDefaults.speed(null, null, false), 0f)
    }

    @Test fun savedChoicesAndOldImplicitDefaultsArePreserved() {
        assertEquals("piper-ryan", VoiceDefaults.voice("piper-ryan", true))
        assertEquals(0.9f, VoiceDefaults.speed(0.9f, "piper-ryan", true), 0f)
        assertEquals("piper-alan", VoiceDefaults.voice(null, true))
        assertEquals(1.0f, VoiceDefaults.speed(null, null, true), 0f)
    }
}

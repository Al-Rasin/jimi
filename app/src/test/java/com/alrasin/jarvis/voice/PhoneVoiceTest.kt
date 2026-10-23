package com.alrasin.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneVoiceTest {
    @Test
    fun defaultIsTheSiriLikeUsFemaleGoogleVoice() {
        val v = Voices.byId(Voices.DEFAULT)
        assertEquals("google:en-us-x-iob-local", v.id)
        assertEquals(Engine.ANDROID, v.pack.engine)
        assertEquals("US", v.accent)
        assertTrue(!v.male)
    }

    @Test
    fun benchmarkCanLoadAnyGoogleVoiceByEngineName() {
        val v = Voices.android("en-gb-x-gbd-local")
        assertEquals("google:en-gb-x-gbd-local", v.id)
        assertEquals(Engine.ANDROID, v.pack.engine)
    }
}

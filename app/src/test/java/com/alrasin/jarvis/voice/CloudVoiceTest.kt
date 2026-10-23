package com.alrasin.jarvis.voice

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudVoiceTest {
    @Test
    fun parsesPcmFromInlineData() {
        val pcm = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())   // 16384, -16384
        val body = """{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;rate=24000","data":"${Base64.getEncoder().encodeToString(pcm)}"}}]}}]}"""
        val audio = GeminiVoice.parse(body)!!
        assertEquals(24_000, audio.sampleRate)
        assertEquals(0.5f, audio.samples[0], 1e-6f)
        assertEquals(-0.5f, audio.samples[1], 1e-6f)
    }

    @Test
    fun noAudioPartGivesNull() {
        assertNull(GeminiVoice.parse("""{"candidates":[{"content":{"parts":[{"text":"hi"}]}}]}"""))
        assertNull(GeminiVoice.parse("""{"error":{"code":404}}"""))
    }

    @Test
    fun requestHoldsVoiceAndAudioModality() {
        val body = GeminiVoice.request("Hello there.", "Kore")
        assertTrue("\"voiceName\":\"Kore\"" in body)
        assertTrue("\"AUDIO\"" in body)
        assertTrue("Hello there." in body)
    }

    @Test
    fun onlyCloudWrittenTextIsSpokenByTheCloudVoice() {
        CloudSpeech.clear()
        CloudSpeech.mark("The Eiffel Tower is in Paris, France.")
        assertTrue(CloudSpeech.fromCloud("is in Paris,"))
        assertTrue(CloudSpeech.fromCloud("The Eiffel Tower is in Paris, France."))
        assertFalse(CloudSpeech.fromCloud("You have 3 new messages from Mom."))
        assertFalse(CloudSpeech.fromCloud(""))
    }

    @Test
    fun unknownCloudVoiceFallsBackToDefault() {
        assertEquals(GeminiVoice.DEFAULT_VOICE, GeminiVoice.byId("nope").id)
        assertEquals("Kore", GeminiVoice.byId("Kore").id)
    }

    @Test
    fun phoneVoiceIdsRoundTrip() {
        val v = Voices.android("en-gb-x-rjs-local")
        assertEquals("google:en-gb-x-rjs-local", v.id)
        assertEquals("UK", v.accent)
        assertTrue(v.male)
        assertEquals(Engine.ANDROID, Voices.byId(v.id).pack.engine)
        assertFalse(Voices.android("en-us-x-iob-local").male)
    }
}

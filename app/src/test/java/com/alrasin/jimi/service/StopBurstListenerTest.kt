package com.alrasin.jimi.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StopBurstListenerTest {
    private val quiet = ShortArray(1280)
    private val loud = ShortArray(1280) { 3000 }

    private fun feed(listener: StopBurstListener, cleaned: List<ShortArray>): String? =
        cleaned.firstNotNullOfOrNull { listener.accept(it, it, "the city goes back", runInline = true) }

    @Test fun decodesClipsAfterBurstAndReportsStop() {
        val clips = ArrayList<Int>()
        val listener = StopBurstListener { clips.add(it.size); "Stop" }
        assertEquals("Stop", feed(listener, List(10) { quiet } + List(8) { loud }))
        assertEquals(12800, clips.first())
    }

    @Test fun ignoresEchoText() {
        var calls = 0
        val listener = StopBurstListener { calls++; "the city" }
        assertNull(feed(listener, List(10) { quiet } + List(8) { loud }))
        assertEquals(4, calls)
    }

    @Test fun quietAudioNeverDecodes() {
        val listener = StopBurstListener { error("should not decode") }
        assertNull(feed(listener, List(40) { quiet }))
    }

    @Test fun continuousLoudAudioIsNotANewOnset() {
        var calls = 0
        val listener = StopBurstListener { calls++; "" }
        feed(listener, List(10) { quiet } + List(30) { loud })
        assertEquals(4, calls)
    }
}

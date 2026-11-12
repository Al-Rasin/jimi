package com.alrasin.jarvis.service

import com.alrasin.jarvis.voice.BargeIn
import com.alrasin.jarvis.voice.ScreenAgentInterrupt
import com.alrasin.jarvis.voice.SpeechToText
import com.alrasin.jarvis.voice.WakeWordDetector

/** Hears "stop" / "cancel" while the screen agent works; each try listens up to 3 s. */
class AgentStopListener(private val stt: SpeechToText?) {
    private val phrases = ScreenAgentInterrupt()
    private var session: SpeechToText.Session? = null
    private var chunks = 0

    fun canListen(agentRunning: Boolean, speechBusy: Boolean, nowMs: Long): Boolean =
        phrases.canListen(agentRunning, speechBusy, nowMs)

    /** Feeds one mic chunk; returns the stop phrase once it is sure, else null. */
    fun accept(chunk: ShortArray, nowMs: Long): String? {
        val stt = stt ?: return null
        val stop = session ?: stt.session(waitStart = 3f).also { session = it }
        stop.accept(chunk)
        val phrase = phrases.accept(stop.text, stop.done, nowMs)
        if (phrase != null || stop.done || ++chunks * chunk.size >= WakeWordDetector.SAMPLE_RATE * 3) reset()
        return phrase
    }

    /** Ends the current try (keeps the phrase being confirmed). */
    fun close() {
        session?.close()
        session = null
        chunks = 0
    }

    fun reset() {
        close()
        phrases.reset()
    }
}

/** Hears "stop" said over Jimi's own voice (barge-in), ignoring echo of what is playing. */
class SpeechStopListener(private val stt: SpeechToText) {
    private var session: SpeechToText.Session? = null
    private var chunks = 0

    /** Feeds one mic chunk; true when the user said a stop word that is not playback echo. */
    fun accept(chunk: ShortArray, playbackText: String): Boolean {
        val candidate = session ?: stt.session().also { session = it; chunks = 0 }
        candidate.accept(chunk)
        val stop = BargeIn.isInterruption(candidate.text) && !BargeIn.isPlaybackEcho(candidate.text, playbackText)
        if (candidate.done || ++chunks >= 25) close()
        return stop
    }

    fun close() {
        session?.close()
        session = null
        chunks = 0
    }
}

package com.alrasin.jimi.service

import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.voice.BargeIn
import com.alrasin.jimi.voice.ScreenAgentInterrupt
import com.alrasin.jimi.voice.SpeechToText
import com.alrasin.jimi.voice.WakeWordDetector

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
    fun accept(chunk: ShortArray, playbackText: String, echoCanceled: Boolean): Boolean {
        val candidate = session ?: stt.session().also { session = it; chunks = 0 }
        candidate.accept(chunk)
        val text = candidate.text
        val stop = BargeIn.isInterruption(text, echoCanceled) && !BargeIn.isPlaybackEcho(text, playbackText)
        if (stop) JimiLog.i("barge-in heard \"$text\"")
        // Silent tries restart every 2 s; a try with words runs to 4 s so a "stop" at the edge is not cut in half.
        val limit = if (text.isBlank()) SILENT_CHUNKS else WORD_CHUNKS
        if (stop || candidate.done || ++chunks >= limit) {
            if (!stop && text.isNotBlank()) JimiLog.i("barge-in ignored \"$text\"")
            close()
        }
        return stop
    }

    fun close() {
        session?.close()
        session = null
        chunks = 0
    }

    private companion object {
        const val SILENT_CHUNKS = 25
        const val WORD_CHUNKS = 50
    }
}

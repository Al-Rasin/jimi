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
    private var spoke = false
    private var quiet = 0

    /** Feeds one mic chunk; true when the user said a stop word that is not playback echo. */
    fun accept(chunk: ShortArray, playbackText: String, echoCanceled: Boolean): Boolean {
        val candidate = session ?: stt.session().also { session = it; chunks = 0; spoke = false; quiet = 0 }
        candidate.accept(chunk)
        chunks++
        if (rms(chunk) > SPEECH_RMS) { spoke = true; quiet = 0 } else quiet++
        var text = candidate.text
        var stop = BargeIn.hasStopOverEcho(text, playbackText, echoCanceled)
        // The echo canceler leaves near silence between words, so a short quiet gap marks the end of what was said.
        // The streaming text often stays blank for a lone "stop" until the decoder is flushed (D-103).
        val ended = (spoke && quiet >= QUIET_CHUNKS) || candidate.done ||
            chunks >= if (text.isBlank() && !spoke) SILENT_CHUNKS else WORD_CHUNKS
        if (!stop && ended && (spoke || text.isNotBlank())) {
            text = candidate.finish()
            stop = BargeIn.hasStopOverEcho(text, playbackText, echoCanceled)
        }
        if (stop) JimiLog.i("barge-in heard \"$text\"")
        if (stop || ended) {
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
        const val SILENT_CHUNKS = 25   // 2 s
        const val WORD_CHUNKS = 50     // 4 s
        const val QUIET_CHUNKS = 3     // 240 ms
        const val SPEECH_RMS = 300.0
    }
}

/**
 * Barge-in "stop" with the software echo canceler (D-103). The canceler's output is near silent while only Jimi
 * talks, so a loud burst there marks the user starting to speak; but it also cuts the start of "stop", so the
 * words are read from the raw mic: a few short clips around the burst, each decoded fresh, off the mic thread.
 * [decode] turns a clip into text (blank on failure).
 */
class StopBurstListener(private val decode: (ShortArray) -> String) : AutoCloseable {
    private val history = ArrayDeque<ShortArray>()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val heard = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var quiet = QUIET_BEFORE
    private var waiting = -1
    private var generation = 0

    /**
     * Feeds the raw mic chunk and the same chunk after the echo canceler; returns the heard text once a clip
     * holds "stop", else null. With [runInline] the clips are decoded on the caller's thread (tests).
     */
    fun accept(raw: ShortArray, cleaned: ShortArray, playbackText: String, runInline: Boolean = false): String? {
        heard.getAndSet(null)?.let { return it }
        history.addLast(raw.copyOf())
        while (history.size > HISTORY_CHUNKS) history.removeFirst()
        if (waiting >= 0) {
            if (++waiting >= AFTER_CHUNKS) {
                waiting = -1
                quiet = 0
                launch(clips(raw.size), playbackText, runInline)
                return heard.getAndSet(null)
            }
            return null
        }
        val loud = rms(cleaned) > ONSET_RMS
        if (loud && quiet >= QUIET_BEFORE && !busy.get()) waiting = 1 else quiet = if (loud) 0 else quiet + 1
        return null
    }

    /** Clips (start before onset, length) cut from the history; the onset chunk is [AFTER_CHUNKS] from the end. */
    private fun clips(chunkSize: Int): List<ShortArray> {
        val all = CommandAudio.join(history.toList())
        val onset = all.size - AFTER_CHUNKS * chunkSize
        return WINDOWS.map { (pre, len) ->
            val from = (onset - pre).coerceAtLeast(0)
            all.copyOfRange(from, (from + len).coerceAtMost(all.size))
        }
    }

    private fun launch(clips: List<ShortArray>, playbackText: String, runInline: Boolean) {
        val gen = generation
        val job = Runnable {
            try {
                for (clip in clips) {
                    val text = runCatching { decode(clip) }.getOrDefault("")
                    if (gen != generation) return@Runnable
                    if (BargeIn.isStopClip(text, playbackText)) {
                        JimiLog.i("barge-in heard \"$text\"")
                        heard.set(text)
                        return@Runnable
                    }
                    if (text.isNotBlank()) JimiLog.i("barge-in ignored \"$text\"")
                }
            } finally {
                busy.set(false)
            }
        }
        busy.set(true)
        if (runInline) job.run() else worker.execute(job)
    }

    /** Forgets the current reply's audio and any pending result. */
    fun reset() {
        generation++
        history.clear()
        heard.set(null)
        waiting = -1
        quiet = QUIET_BEFORE
    }

    override fun close() {
        reset()
        worker.shutdownNow()
        // The clip decoder uses the shared STT model: let a running decode end before the model is freed.
        worker.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)
    }

    private companion object {
        const val RATE = WakeWordDetector.SAMPLE_RATE
        const val ONSET_RMS = 1200.0
        const val QUIET_BEFORE = 3      // chunks below ONSET_RMS before a burst counts as a new onset
        const val AFTER_CHUNKS = 8      // onset chunk + 7 more (~0.64 s) before cutting clips
        const val HISTORY_CHUNKS = 16
        // (start before onset, length) in samples; tried in order, first "stop" wins.
        val WINDOWS = listOf(
            RATE / 5 to RATE * 4 / 5, RATE / 10 to RATE * 7 / 10, RATE * 3 / 10 to RATE * 9 / 10, RATE / 5 to RATE / 2,
        )
    }
}

private fun rms(chunk: ShortArray): Double {
    var sum = 0.0
    for (s in chunk) sum += s.toDouble() * s
    return kotlin.math.sqrt(sum / chunk.size.coerceAtLeast(1))
}

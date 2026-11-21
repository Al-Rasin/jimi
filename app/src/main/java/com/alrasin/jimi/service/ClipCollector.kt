package com.alrasin.jimi.service

import android.content.Context
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.data.WakeEvent
import com.alrasin.jimi.data.WakeEventDao
import com.alrasin.jimi.voice.AudioRing
import com.alrasin.jimi.voice.Chimes
import com.alrasin.jimi.voice.Wav
import com.alrasin.jimi.voice.WakeFeedback as WakeFeedbackSignal
import com.alrasin.jimi.voice.WakeWordDetector

/** Decides when to save a clip: real wakes, near misses, and "Missed it" taps. */
class ClipCollector(
    private val context: Context,
    private val dao: WakeEventDao,
    /** True once after the user tapped "Missed it". */
    private val takeMissed: () -> Boolean,
) {
    private var lastWakeMs = 0L
    private var lastNearMs = 0L
    private var pendingKind: String? = null
    private var pendingPeak = 0f
    private var pendingChunks = 0
    private var wakeAudio: ShortArray? = null
    var lastWakeId: Long? = null
        private set
    var lastWakePeak = 0f
        private set

    /**
     * Returns true when the wake word was just heard.
     * [confirm]: checks a near miss scoring >= [CHECK_MIN] for the name with speech-to-text (D-098): real
     * "Hey Jimi" calls often scored 0.78–0.91, below the bar. Null when a wake must not start now.
     * With a check available, a low slider wakes at once only from [SURE_SCORE]; scores between the slider and it
     * must say the name too (D-099): at 0.66, TV talk scored 0.66–0.81, as high as the user's real calls.
     * [verified]: false when the wake verifier (D-100) doubts a high score; with a check available such a score
     * goes through the name check instead of waking at once.
     */
    suspend fun onChunk(
        score: Float, thr: Float, ring: AudioRing, tone: Chimes,
        confirm: (suspend (ShortArray) -> Boolean)? = null,
        verified: Boolean = true,
    ): Boolean {
        val now = System.currentTimeMillis()
        if (takeMissed()) {
            save(WakeEvent.VERDICT_MISSED, 0f, thr, ring.last(WakeWordDetector.SAMPLE_RATE * MISSED_SECONDS))
            return false
        }
        val instant = instantBar(thr, canCheck = confirm != null)
        val checkFrom = checkBar(thr)
        val wakesNow = wakesNow(score, instant, verified, canCheck = confirm != null)
        if (wakesNow && now - lastWakeMs > WAKE_COOLDOWN_MS) {
            startWake(now, score, ring.last(CLIP_SAMPLES))
            return true
        }
        // A near miss worth checking skips the save cooldown: a retry right after a miss must get its chance too.
        if (pendingKind == null && score >= NEAR_MIN && !wakesNow && now - lastWakeMs > WAKE_COOLDOWN_MS &&
            (now - lastNearMs > NEAR_COOLDOWN_MS || score >= checkFrom)
        ) {
            pendingKind = WakeEvent.VERDICT_NEAR
            pendingPeak = score
            pendingChunks = AFTER_CHUNKS
            return false
        }
        val kind = pendingKind ?: return false
        if (kind == WakeEvent.VERDICT_NEAR && score > pendingPeak) pendingChunks = AFTER_CHUNKS   // keep ~0.5 s after the peak
        pendingPeak = maxOf(pendingPeak, score)
        if (kind == WakeEvent.VERDICT_NEAR && pendingPeak >= instant && confirm == null) {
            pendingKind = null   // turned into a real wake; next chunk handles it
            return false
        }
        if (--pendingChunks > 0) return false
        pendingKind = null
        if (kind == WakeEvent.VERDICT_NEAR && confirm != null && pendingPeak >= checkFrom) {
            val heard = ring.last(CLIP_SAMPLES)
            if (confirm(heard)) {
                JimiLog.i("wake by name check, score=${"%.2f".format(pendingPeak)}")
                startWake(now, pendingPeak, heard)
                pendingChunks = 1   // clip already holds the name; save it on the next chunk
                return true
            }
            if (pendingPeak >= thr) JimiLog.i("wake skipped, no name heard, score=${"%.2f".format(pendingPeak)}")
        }
        if (kind == WakeEvent.VERDICT_NEAR) lastNearMs = now
        val audio = if (kind == WakeEvent.VERDICT_NONE) wakeAudio else null
        wakeAudio = null
        val id = save(kind, pendingPeak, thr, audio ?: ring.last(CLIP_SAMPLES))
        if (kind == WakeEvent.VERDICT_NONE) {
            lastWakeId = id
            lastWakePeak = pendingPeak
            CoreState._wakeId.value = id   // asked in the wake panel, not in the notification shade
        }
        return false
    }

    private fun startWake(now: Long, score: Float, audio: ShortArray) {
        lastWakeMs = now
        WakeFeedbackSignal.signal(context)
        pendingKind = WakeEvent.VERDICT_NONE
        pendingPeak = score
        pendingChunks = AFTER_CHUNKS
        // Copied now: the command capture runs next and resets the ring, so a later copy held
        // only 0.5 s of after-command audio instead of the "Hey Jimi" that woke us.
        wakeAudio = audio
        lastWakeId = null
        CoreState._wakeId.value = null   // the panel asks "Did you call me?" once this wake is saved
    }

    private suspend fun save(verdict: String, score: Float, thr: Float, audio: ShortArray): Long {
        val time = System.currentTimeMillis()
        val file = WakeFeedback.clipFile(context, time, score, verdict)
        Wav.write(file, audio)
        CommandAudio.keepNewest(WakeFeedback.clipDir(context), MAX_CLIPS)   // ~40 MB; the folder grew without end
        val id = dao.insert(WakeEvent(timeMs = time, score = score, threshold = thr, clipPath = file.path, verdict = verdict))
        JimiLog.i("wake #$id $verdict score=${"%.2f".format(score)}")
        return id
    }

    internal companion object {
        fun instantBar(thr: Float, canCheck: Boolean): Float = if (canCheck) maxOf(thr, SURE_SCORE) else thr
        fun checkBar(thr: Float): Float = minOf(thr, CHECK_MIN)
        /** A doubted score (D-100 verifier) waits for the name check when one is available. */
        fun wakesNow(score: Float, instant: Float, verified: Boolean, canCheck: Boolean) =
            score >= instant && (verified || !canCheck)

        private const val CLIP_SAMPLES = 40_000       // 2.5 s per wake clip
        private const val MISSED_SECONDS = 3
        private const val MAX_CLIPS = 500
        private const val AFTER_CHUNKS = 6          // keep ~0.5 s after the peak in the clip
        private const val WAKE_COOLDOWN_MS = 2_000L
        private const val NEAR_MIN = 0.2f
        private const val CHECK_MIN = 0.7f
        private const val SURE_SCORE = 0.88f        // D-096 default bar
        private const val NEAR_COOLDOWN_MS = 30_000L
    }
}

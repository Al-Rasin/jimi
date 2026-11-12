package com.alrasin.jarvis.service

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.voice.AudioRing
import com.alrasin.jarvis.voice.Chimes
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakeFeedback as WakeFeedbackSignal
import com.alrasin.jarvis.voice.WakeWordDetector

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
    var lastWakeId: Long? = null
        private set
    var lastWakePeak = 0f
        private set

    /** Returns true when the wake word was just heard. */
    suspend fun onChunk(score: Float, thr: Float, ring: AudioRing, tone: Chimes): Boolean {
        val now = System.currentTimeMillis()
        if (takeMissed()) {
            save(WakeEvent.VERDICT_MISSED, 0f, thr, ring.last(WakeWordDetector.SAMPLE_RATE * MISSED_SECONDS))
            return false
        }
        if (score >= thr && now - lastWakeMs > WAKE_COOLDOWN_MS) {
            lastWakeMs = now
            WakeFeedbackSignal.signal(context)
            pendingKind = WakeEvent.VERDICT_NONE
            pendingPeak = score
            pendingChunks = AFTER_CHUNKS
            lastWakeId = null
            return true
        }
        if (pendingKind == null && score >= NEAR_MIN && score < thr &&
            now - lastNearMs > NEAR_COOLDOWN_MS && now - lastWakeMs > WAKE_COOLDOWN_MS
        ) {
            pendingKind = WakeEvent.VERDICT_NEAR
            pendingPeak = score
            pendingChunks = AFTER_CHUNKS
            return false
        }
        val kind = pendingKind ?: return false
        pendingPeak = maxOf(pendingPeak, score)
        if (kind == WakeEvent.VERDICT_NEAR && pendingPeak >= thr) {
            pendingKind = null   // turned into a real wake; next chunk handles it
            return false
        }
        if (--pendingChunks > 0) return false
        pendingKind = null
        if (kind == WakeEvent.VERDICT_NEAR) lastNearMs = now
        val id = save(kind, pendingPeak, thr, ring.last(CLIP_SAMPLES))
        if (kind == WakeEvent.VERDICT_NONE) {
            lastWakeId = id
            lastWakePeak = pendingPeak
            Notifications.showWakeCheck(context, id, pendingPeak)
        }
        return false
    }

    private suspend fun save(verdict: String, score: Float, thr: Float, audio: ShortArray): Long {
        val time = System.currentTimeMillis()
        val file = WakeFeedback.clipFile(context, time, score, verdict)
        Wav.write(file, audio)
        val id = dao.insert(WakeEvent(timeMs = time, score = score, threshold = thr, clipPath = file.path, verdict = verdict))
        JarvisLog.i("wake #$id $verdict score=${"%.2f".format(score)}")
        return id
    }

    private companion object {
        const val CLIP_SAMPLES = 40_000       // 2.5 s per wake clip
        const val MISSED_SECONDS = 3
        const val AFTER_CHUNKS = 6           // keep ~0.5 s after the peak in the clip
        const val WAKE_COOLDOWN_MS = 2_000L
        const val NEAR_MIN = 0.2f
        const val NEAR_COOLDOWN_MS = 30_000L
    }
}

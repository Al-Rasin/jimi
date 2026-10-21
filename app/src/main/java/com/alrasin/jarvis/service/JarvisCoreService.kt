package com.alrasin.jarvis.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.voice.AudioRing
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Always-on core (PLAN 2.1) — for now: listens for "Hey Jimi" (3.1) and asks "Was that you?" (3.6, D-023).
 */
@AndroidEntryPoint
class JarvisCoreService : LifecycleService() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var dao: WakeEventDao

    @Volatile private var threshold = Settings.DEFAULT_THRESHOLD
    @Volatile private var missedRequested = false
    private var listenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch { settings.setListeningEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MISSED -> missedRequested = true
        }
        startForeground(
            Notifications.ID_SERVICE,
            Notifications.listening(this, "Say \"Hey Jimi\""),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        if (listenJob == null) startListening()
        return START_STICKY
    }

    private fun startListening() {
        lifecycleScope.launch { settings.wakeThreshold.collect { threshold = it } }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen")
            .apply { acquire() }
        listenJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                listenLoop()
            } catch (t: Throwable) {
                JarvisLog.e("listening stopped", t)
                _running.value = false
            }
        }
    }

    @SuppressLint("MissingPermission") // checked just below
    private suspend fun listenLoop() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            JarvisLog.e("no microphone permission")
            stopSelf()
            return
        }
        val models = WakeModels.load(this)
        JarvisLog.i("wake model: ${models.wakeName}")
        _modelName.value = models.wakeName
        val detector = WakeWordDetector(models.mel, models.embedding, models.wake)
        val chunk = ShortArray(WakeWordDetector.CHUNK)
        val ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, WakeWordDetector.CHUNK * 2 * 4),
        )
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        val clips = ClipCollector()
        try {
            rec.startRecording()
            _running.value = true
            JarvisLog.i("listening")
            while (currentCoroutineContext().isActive) {
                var got = 0
                while (got < chunk.size) {
                    val n = rec.read(chunk, got, chunk.size - got)
                    if (n < 0) error("AudioRecord.read = $n")
                    got += n
                }
                ring.write(chunk)
                val score = detector.process(chunk)
                _lastScore.value = score
                clips.onChunk(score, threshold, ring, tone)
            }
        } finally {
            rec.stop()
            rec.release()
            tone.release()
            detector.close()
            _running.value = false
        }
    }

    /** Decides when to save a clip: real wakes, near misses, and "Missed it" taps. */
    private inner class ClipCollector {
        private var lastWakeMs = 0L
        private var lastNearMs = 0L
        private var pendingKind: String? = null
        private var pendingPeak = 0f
        private var pendingChunks = 0

        suspend fun onChunk(score: Float, thr: Float, ring: AudioRing, tone: ToneGenerator) {
            val now = System.currentTimeMillis()
            if (missedRequested) {
                missedRequested = false
                save(WakeEvent.VERDICT_MISSED, 0f, thr, ring.last(WakeWordDetector.SAMPLE_RATE * MISSED_SECONDS))
                return
            }
            if (score >= thr && now - lastWakeMs > WAKE_COOLDOWN_MS) {
                lastWakeMs = now
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
                pendingKind = WakeEvent.VERDICT_NONE
                pendingPeak = score
                pendingChunks = AFTER_CHUNKS
                return
            }
            if (pendingKind == null && score >= NEAR_MIN && score < thr &&
                now - lastNearMs > NEAR_COOLDOWN_MS && now - lastWakeMs > WAKE_COOLDOWN_MS
            ) {
                pendingKind = WakeEvent.VERDICT_NEAR
                pendingPeak = score
                pendingChunks = AFTER_CHUNKS
                return
            }
            val kind = pendingKind ?: return
            pendingPeak = maxOf(pendingPeak, score)
            if (kind == WakeEvent.VERDICT_NEAR && pendingPeak >= thr) {
                pendingKind = null   // turned into a real wake; next chunk handles it
                return
            }
            if (--pendingChunks > 0) return
            pendingKind = null
            if (kind == WakeEvent.VERDICT_NEAR) lastNearMs = now
            val id = save(kind, pendingPeak, thr, ring.last(CLIP_SAMPLES))
            if (kind == WakeEvent.VERDICT_NONE) Notifications.showWakeCheck(this@JarvisCoreService, id, pendingPeak)
        }

        private suspend fun save(verdict: String, score: Float, thr: Float, audio: ShortArray): Long {
            val time = System.currentTimeMillis()
            val file = WakeFeedback.clipFile(this@JarvisCoreService, time, score, verdict)
            Wav.write(file, audio)
            val id = dao.insert(WakeEvent(timeMs = time, score = score, threshold = thr, clipPath = file.path, verdict = verdict))
            JarvisLog.i("wake #$id $verdict score=${"%.2f".format(score)}")
            return id
        }
    }

    override fun onDestroy() {
        listenJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.alrasin.jarvis.STOP"
        const val ACTION_MISSED = "com.alrasin.jarvis.MISSED"
        private const val RING_SECONDS = 4
        private const val CLIP_SAMPLES = 40_000       // 2.5 s per wake clip
        private const val MISSED_SECONDS = 3
        private const val AFTER_CHUNKS = 6           // keep ~0.5 s after the peak in the clip
        private const val WAKE_COOLDOWN_MS = 2_000L
        private const val NEAR_MIN = 0.2f
        private const val NEAR_COOLDOWN_MS = 30_000L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _modelName = MutableStateFlow("")
        val modelName: StateFlow<String> = _modelName
        private val _lastScore = MutableStateFlow(0f)
        val lastScore: StateFlow<Float> = _lastScore

        fun start(context: Context) =
            context.startForegroundService(Intent(context, JarvisCoreService::class.java))

        /** Save the last 3 s as a "missed" clip (wake word was said but not detected). */
        fun missed(context: Context) = context.startService(
            Intent(context, JarvisCoreService::class.java).setAction(ACTION_MISSED)
        )

        fun stop(context: Context) = context.startService(
            Intent(context, JarvisCoreService::class.java).setAction(ACTION_STOP)
        )
    }
}

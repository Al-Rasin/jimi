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
import com.alrasin.jarvis.voice.SpeechToText
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.WakePhrase
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * Always-on core (PLAN 2.1) — for now: listens for "Hey Jimi" (3.1), asks "Was that you?" (3.6, D-023),
 * then hears the command, shows it and says "You said …" (3.2–3.4 echo loop).
 */
@AndroidEntryPoint
class JarvisCoreService : LifecycleService() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var dao: WakeEventDao

    @Volatile private var threshold = Settings.DEFAULT_THRESHOLD
    @Volatile private var missedRequested = false
    @Volatile private var speaking = false
    private var tts: Deferred<TextToSpeech?>? = null
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
        val stt = runCatching { SpeechToText(this) }
            .onSuccess { JarvisLog.i("speech-to-text: ${it.modelName}") }
            .onFailure { JarvisLog.e("speech-to-text not loaded (run tools/fetch_speech.sh)", it) }
            .getOrNull()
        tts = lifecycleScope.async(Dispatchers.IO) {
            runCatching { TextToSpeech(this@JarvisCoreService) }
                .onFailure { JarvisLog.e("voice not loaded (run tools/fetch_speech.sh)", it) }
                .getOrNull()
        }
        var command: SpeechToText.Session? = null
        var commandChunks = 0
        val commandAudio = ArrayList<ShortArray>()
        var wasBusy = false
        val chunk = ShortArray(WakeWordDetector.CHUNK)
        val ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, WakeWordDetector.SAMPLE_RATE * 2),   // 1 s: room for slow STT decode steps
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
                val cmd = command
                if (cmd != null) {
                    clips.onChunk(0f, threshold, ring, tone)   // only finishes the pending wake clip
                    cmd.accept(chunk)
                    commandAudio.add(chunk.copyOf())
                    _heard.value = WakePhrase.strip(cmd.text)
                    if (cmd.done || ++commandChunks >= COMMAND_MAX_CHUNKS) {
                        command = null
                        cmd.close()
                        saveCommand(commandAudio, cmd.text)
                        onCommand(WakePhrase.strip(cmd.text), clips.lastWakeId, clips.lastWakePeak, tone)
                    }
                    continue
                }
                if (speaking) { wasBusy = true; continue }
                if (wasBusy) { wasBusy = false; detector.reset() }   // drop audio from before the command
                val score = detector.process(chunk)
                _lastScore.value = score
                if (clips.onChunk(score, threshold, ring, tone) && stt != null) {
                    val preRoll = ring.last(PRE_ROLL_SAMPLES)
                    command = stt.session(preRoll)
                    commandAudio.clear()
                    commandAudio.add(preRoll)
                    commandChunks = 0
                    wasBusy = true
                    _heard.value = ""
                    _phase.value = PHASE_HEARING
                }
            }
        } finally {
            command?.close()
            stt?.close()
            rec.stop()
            rec.release()
            tone.release()
            detector.close()
            _running.value = false
            _phase.value = PHASE_WAKE
        }
    }

    /** Keeps each command's audio + raw STT text, to test better STT models on the user's real voice. */
    private fun saveCommand(parts: List<ShortArray>, rawText: String) {
        runCatching {
            val audio = ShortArray(parts.sumOf { it.size })
            var pos = 0
            for (p in parts) { p.copyInto(audio, pos); pos += p.size }
            val dir = File(getExternalFilesDir(null), "commands").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            Wav.write(File(dir, "$name.wav"), audio)
            File(dir, "$name.txt").writeText(rawText + "\n")
        }.onFailure { JarvisLog.e("could not save command audio", it) }
    }

    /** Echo loop (PLAN 3.4): show what was heard (app + notification, lock screen ok) and say it back. */
    private fun onCommand(text: String, wakeId: Long?, wakeScore: Float, tone: ToneGenerator) {
        JarvisLog.i("heard: \"$text\"")
        _heard.value = text.ifEmpty { "(nothing heard)" }
        if (wakeId != null) Notifications.showWakeCheck(this, wakeId, wakeScore, heard = text.ifEmpty { "(nothing)" })
        if (text.isEmpty()) {
            tone.startTone(ToneGenerator.TONE_PROP_NACK, 200)
            _phase.value = PHASE_WAKE
            return
        }
        speaking = true
        _phase.value = PHASE_SPEAKING
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                tts?.await()?.speak("You said: $text")
            } catch (t: Throwable) {
                JarvisLog.e("speak failed", t)
            } finally {
                speaking = false
                _phase.value = PHASE_WAKE
            }
        }
    }

    /** Decides when to save a clip: real wakes, near misses, and "Missed it" taps. */
    private inner class ClipCollector {
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
        suspend fun onChunk(score: Float, thr: Float, ring: AudioRing, tone: ToneGenerator): Boolean {
            val now = System.currentTimeMillis()
            if (missedRequested) {
                missedRequested = false
                save(WakeEvent.VERDICT_MISSED, 0f, thr, ring.last(WakeWordDetector.SAMPLE_RATE * MISSED_SECONDS))
                return false
            }
            if (score >= thr && now - lastWakeMs > WAKE_COOLDOWN_MS) {
                lastWakeMs = now
                tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 150)
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
                Notifications.showWakeCheck(this@JarvisCoreService, id, pendingPeak)
            }
            return false
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onDestroy() {
        listenJob?.cancel()
        tts?.let { d -> if (d.isCompleted && !speaking) d.getCompleted()?.close() else d.cancel() }
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
        private const val PRE_ROLL_SAMPLES = 16_000   // 1 s before the wake: "…jimi turn" → "turn" is kept
        private const val COMMAND_MAX_CHUNKS = 150   // ~12 s safety stop (STT also stops itself at 10 s)

        const val PHASE_WAKE = "Say \"Hey Jimi\""
        const val PHASE_HEARING = "Listening to you…"
        const val PHASE_SPEAKING = "Speaking…"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _modelName = MutableStateFlow("")
        val modelName: StateFlow<String> = _modelName
        private val _lastScore = MutableStateFlow(0f)
        val lastScore: StateFlow<Float> = _lastScore
        private val _phase = MutableStateFlow(PHASE_WAKE)
        val phase: StateFlow<String> = _phase
        private val _heard = MutableStateFlow("")
        /** Live (partial) text while hearing a command, then the final text. */
        val heard: StateFlow<String> = _heard

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

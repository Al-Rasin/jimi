package com.alrasin.jarvis.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.agent.ScreenAgentSession
import com.alrasin.jarvis.brain.Assistant
import com.alrasin.jarvis.brain.CloudBrain
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.backgroundWork
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.ui.AssistantActivity
import com.alrasin.jarvis.voice.AudioRing
import com.alrasin.jarvis.voice.BargeIn
import com.alrasin.jarvis.voice.SpeakerVerifier
import com.alrasin.jarvis.voice.SpeechToText
import com.alrasin.jarvis.voice.SecondPassSpeechToText
import com.alrasin.jarvis.voice.SttCorrector
import com.alrasin.jarvis.voice.SttVocabularyLoader
import com.alrasin.jarvis.voice.SttVocabularyUpdates
import com.alrasin.jarvis.voice.PhoneSpeechRecognizer
import com.alrasin.jarvis.voice.SpeechCapturePolicy
import com.alrasin.jarvis.voice.SpeechEngine
import com.alrasin.jarvis.voice.WakePhrase
import com.alrasin.jarvis.data.SpeechEngineSettings
import com.alrasin.jarvis.voice.ScreenAgentInterrupt
import com.alrasin.jarvis.voice.Chimes
import com.alrasin.jarvis.voice.PhraseCache
import com.alrasin.jarvis.voice.SpeechText
import com.alrasin.jarvis.voice.Engine
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.WakeFeedback as WakeFeedbackSignal
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import com.alrasin.jarvis.voice.Voices
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Always-on core (PLAN 2.1): listens for "Hey Jimi" (3.1), hears the command (3.2), sends it to the
 * [Assistant] and speaks the answer sentence by sentence (3.3). After the answer it keeps listening for
 * a few seconds without the wake word (follow-up, like Siri / Google Assistant). The panel
 * ([AssistantActivity]) shows what was heard so the user can fix it without a pop-up.
 */
@AndroidEntryPoint
class JarvisCoreService : LifecycleService() {
    @Inject lateinit var settings: Settings
    @Inject lateinit var dao: WakeEventDao
    @Inject lateinit var assistant: Assistant
    @Inject lateinit var models: ModelBrain
    @Inject lateinit var cloud: CloudBrain
    @Inject lateinit var speaker: SpeakerVerifier
    @Inject lateinit var screenAgent: ScreenAgentSession

    @Volatile private var threshold = Settings.DEFAULT_THRESHOLD
    @Volatile private var missedRequested = false
    /** While busy, only echo-aware interruption detection consumes microphone audio. */
    @Volatile private var speaking = false
    @Volatile private var playbackActive = false
    private val agentInterrupt = AtomicReference<String?>(null)
    /** Set to "follow_up" or "tap": the mic loop opens a command session without the wake word. */
    @Volatile private var listenRequest: String? = null
    @Volatile private var spokenWake = true
    @Volatile private var cachedAudio: Map<String, PhraseCache.Audio> = emptyMap()
    @Volatile private var commandStartedAt = 0L
    @Volatile private var timedCommandEpoch = -1
    @Volatile private var firstReplyReadyAt = 0L
    private var firstAudioEpoch = -1
    private var fillerIndex = 0
    @Volatile private var followUp = true
    @Volatile private var showPanel = true
    @Volatile private var voiceId = false
    @Volatile private var speakerThreshold = Settings.DEFAULT_SPEAKER_THRESHOLD
    /** Set by the Settings tab: the mic loop records [ENROLL_CHUNKS] of the owner's voice for voice ID. */
    @Volatile private var enrollRequested = false
    @Volatile private var tts: Deferred<TextToSpeech?>? = null
    private var voiceJob: Job? = null
    /** Generating and closing a voice must not overlap (native code). */
    private val ttsLock = Mutex()
    private var phrases: PhraseCache? = null
    private var phraseWarm: Job? = null
    @Volatile private var speechEpoch = 0
    private val speechState = Any()
    private val thinkingCue = ThinkingCue()
    @Volatile private var replyJob: Job? = null
    @Volatile private var pendingCancel: Job? = null
    @Volatile private var playbackText = ""
    @Volatile private var voiceSpeed = 1.0f
    private val speech = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private val speechLeft = AtomicInteger(0)
    private var batteryWarnedAt = 100
    private var listenJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action != ACTION_STOP) {
            try {
                startForeground(
                    Notifications.ID_SERVICE,
                    Notifications.listening(this, "Say \"Hey Jimi\""),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                )
            } catch (failure: RuntimeException) {
                JarvisLog.e("Open Jimi to grant microphone access and resume listening", failure)
                Notifications.showResumeListening(this)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch { settings.setListeningEnabled(false) }
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_MISSED -> missedRequested = true
            ACTION_SAY -> intent.getStringExtra(EXTRA_TEXT)?.let { announce(it) }
            ACTION_ASK -> intent.getStringExtra(EXTRA_TEXT)?.let {
                ask(it, it, intent.getStringExtra(EXTRA_SOURCE) ?: "typed", null, null, null)
            }
            ACTION_LISTEN -> synchronized(speechState) {
                shutUp()
                listenRequest = "tap"
            }
            ACTION_SHUT_UP -> shutUp()
            ACTION_ENROLL -> enrollRequested = true
        }
        ensureVoice()
        lifecycleScope.launch {
            val on = intent?.action !in QUIET_ACTIONS || settings.listeningEnabled.first()
            if (listenJob?.isActive != true && on) {
                startListening()
                BootReceiver.schedule(this@JarvisCoreService)
            }
        }
        return START_STICKY
    }

    /**
     * Loads the chosen voice and starts the speaker: one coroutine makes the audio for the next sentence
     * while the other plays the current one (Supertonic takes about as long to make as to play).
     * [speechEpoch] goes up on "stop", so audio made before that is thrown away.
     */
    private fun ensureVoice() {
        if (voiceJob != null) return
        lifecycleScope.launch { settings.spokenWake.collect { spokenWake = it } }
        voiceJob = lifecycleScope.launch(Dispatchers.IO) {
            settings.voice.distinctUntilChanged().collect { id -> switchVoice(id) }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            settings.voiceSpeed.distinctUntilChanged().collect { s ->
                ttsLock.withLock {
                    voiceSpeed = s
                    cachedAudio = emptyMap()
                    readyVoice()?.let { v ->
                        v.speed = s
                        warmPhrases(v)
                    }
                }
            }
        }
        val audio = Channel<Triple<Int, String, PhraseCache.Audio>>(1)
        lifecycleScope.launch(Dispatchers.IO) {
            for ((epoch, s) in speech) {
                val made = try {
                    cachedAudio[SpeechText.normalize(s)] ?: ttsLock.withLock {
                        if (epoch == speechEpoch) tts?.await()?.let { v -> audioFor(v, s) } else null
                    }
                } catch (t: Throwable) {
                    JarvisLog.e("speak failed", t)
                    null
                }
                if (made != null && epoch == speechEpoch) audio.send(Triple(epoch, s, made)) else speechLeft.decrementAndGet()
            }
            audio.close()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            for ((epoch, text, a) in audio) {
                try {
                    if (epoch == speechEpoch) {
                        playbackText = text
                        playbackActive = true
                        TextToSpeech.playPcm(a.samples, a.sampleRate, onStarted = {
                            synchronized(speechState) {
                                if (epoch == speechEpoch && epoch == timedCommandEpoch && text !in FILLERS) {
                                    _reply.value = (_reply.value + " " + text).trim()
                                }
                                if (epoch == speechEpoch && epoch == timedCommandEpoch && firstAudioEpoch != epoch && text !in FILLERS) {
                                    firstAudioEpoch = epoch
                                    JarvisLog.i("latency: audio after reply ${SystemClock.elapsedRealtime() - firstReplyReadyAt} ms")
                                    JarvisLog.i("latency: first audio ${SystemClock.elapsedRealtime() - commandStartedAt} ms after command")
                                }
                            }
                        }) { epoch != speechEpoch }
                    }
                } catch (t: Throwable) {
                    JarvisLog.e("play failed", t)
                } finally {
                    playbackText = ""
                    playbackActive = false
                    speechLeft.decrementAndGet()
                }
            }
        }
        lifecycleScope.launch { settings.followUp.collect { followUp = it } }
        lifecycleScope.launch { settings.showPanel.collect { showPanel = it } }
        lifecycleScope.launch { settings.voiceId.collect { voiceId = it } }
        lifecycleScope.launch { settings.speakerThreshold.collect { speakerThreshold = it } }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        lifecycleScope.launch(Dispatchers.IO) { protectFromLowMemoryKiller() }
    }

    /**
     * MIUI's low-memory killer took the service down when another app opened (signal 9 on the phone).
     * With root, lower our oom_score_adj. Android rewrites it when the process state changes, so repeat.
     */
    private suspend fun protectFromLowMemoryKiller() {
        if (!RootShell.available) return
        val path = "/proc/${android.os.Process.myPid()}/oom_score_adj"
        while (currentCoroutineContext().isActive) {
            RootShell.run("[ \$(cat $path) -gt $OOM_ADJ ] && echo $OOM_ADJ > $path || true")
            delay(30_000)
        }
    }

    /** Loads voice [id] (falls back to the built-in voice if it is not downloaded or fails), then frees the old one. */
    private suspend fun switchVoice(id: String) {
        var old: Deferred<TextToSpeech?>? = null
        val next = ttsLock.withLock {
            currentCoroutineContext().ensureActive()
            old = tts
            phraseWarm?.cancel()
            phrases = null
            cachedAudio = emptyMap()
            // Teardown must be able to await and release a native voice even if loading is cancelled.
            lifecycleScope.async(Dispatchers.IO + NonCancellable) {
                val v = Voices.byId(id).takeIf { Voices.installed(this@JarvisCoreService, it.pack) } ?: Voices.byId(Voices.BUILT_IN)
                runCatching { TextToSpeech(this@JarvisCoreService, v, speed = voiceSpeed) }
                    .recoverCatching { if (v.id == Voices.BUILT_IN) throw it else TextToSpeech(this@JarvisCoreService, speed = voiceSpeed) }
                    .onSuccess { JarvisLog.i("voice: ${it.voice.id}") }
                    .onFailure { JarvisLog.e("voice not loaded (run tools/fetch_speech.sh)", it) }
                    .getOrNull()
            }.also { tts = it }
        }
        try {
            val loaded = next.await()
            ttsLock.withLock {
                if (tts === next) loaded?.let {
                    it.speed = voiceSpeed
                    warmPhrases(it)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                old?.await()?.let { o -> ttsLock.withLock { o.close() } }
            }
        }
    }

    /** Common short replies come from [PhraseCache] (made once per voice + speed), the rest is made now. */
    private fun audioFor(v: TextToSpeech, text: String): PhraseCache.Audio {
        val spoken = SpeechText.normalize(text)
        val common = spoken in COMMON_PHRASES
        var cache = if (common) phrases?.takeIf { it.voiceId == v.voice.id && it.speed == v.speed } else null
        cachedAudio[spoken]?.let { return it }
        cache?.get(spoken)?.let {
            cachedAudio = cachedAudio + (spoken to it)
            return it
        }
        val made = v.generate(spoken)
        if (common && cache == null) {
            cache = PhraseCache(File(cacheDir, "phrases"), v.voice.id, v.speed, made.sampleRate)
            phrases = cache
        }
        cache?.put(spoken, made.samples, made.sampleRate)
        val audio = PhraseCache.Audio(made.samples, made.sampleRate)
        if (common) cachedAudio = cachedAudio + (spoken to audio)
        return audio
    }

    /** Warm only feedback on a disposable, single-thread engine; replies never share its lock. */
    private fun warmPhrases(v: TextToSpeech) {
        phraseWarm?.cancel()
        val speed = v.speed
        val voice = v.voice
        val cache = PhraseCache(File(cacheDir, "phrases"), voice.id, speed, v.sampleRate)
        phrases = cache
        phraseWarm = lifecycleScope.launch(Dispatchers.IO) {
            fun idle() = !speaking && speechLeft.get() == 0 && listenRequest == null &&
                _phase.value == PHASE_WAKE && readyVoice() === v && v.speed == speed
            for (text in PhraseCache.COMMON) {
                ensureActive()
                val key = SpeechText.normalize(text)
                cache.get(key)?.let { audio ->
                    ttsLock.withLock {
                        if (readyVoice() === v && v.speed == speed) cachedAudio = cachedAudio + (key to audio)
                    }
                }
            }
            val missing = FEEDBACK_PHRASES.filter { cache.get(SpeechText.normalize(it)) == null }
            if (missing.isEmpty()) return@launch
            while (isActive && !idle()) delay(200)
            ensureActive()
            backgroundWork {
                TextToSpeech(this@JarvisCoreService, voice, threads = 1, speed = speed).use { warmer ->
                    for (text in missing) {
                        if (!isActive || !idle()) break
                        val made = warmer.generate(text)
                        if (!isActive || !idle()) break
                        cache.put(SpeechText.normalize(text), made.samples, made.sampleRate)
                    }
                }
            }
            for (text in missing) {
                ensureActive()
                cache.get(SpeechText.normalize(text))?.let { audio ->
                    ttsLock.withLock {
                        if (readyVoice() === v && v.speed == speed)
                            cachedAudio = cachedAudio + (SpeechText.normalize(text) to audio)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun readyVoice(): TextToSpeech? = tts?.takeIf { it.isCompleted }?.getCompleted()

    private fun acknowledgeWake(tone: Chimes) {
        // While the user talks: open the cloud connection, or load the local tool model when the cloud is not usable.
        lifecycleScope.launch(Dispatchers.IO) { if (cloud.enabled()) cloud.warm() else models.preloadTools() }
        val audio = cachedAudio[SpeechText.normalize("Uh-huh.")]
        if (!spokenWake || audio == null) { tone.wake(); return }
        val epoch = speechEpoch
        lifecycleScope.launch(Dispatchers.IO) {
            TextToSpeech.playPcm(audio.samples, audio.sampleRate) { epoch != speechEpoch }
        }
    }

    private fun say(text: String, epoch: Int = speechEpoch) {
        val chunks = if (cachedAudio.containsKey(SpeechText.normalize(text))) listOf(SpeechText.normalize(text))
            else SpeechText.chunks(text, maxWords = 5,
                firstWords = if (epoch == timedCommandEpoch && firstAudioEpoch != epoch && speechLeft.get() == 0) 2 else 5)
        synchronized(speechState) {
            if (epoch != speechEpoch) return
            for (chunk in chunks) {
                speechLeft.incrementAndGet()
                if (speech.trySend(epoch to chunk).isFailure) speechLeft.decrementAndGet()
            }
        }
    }

    private suspend fun awaitSpeech() {
        while (speechLeft.get() > 0) delay(50)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun shutUp(cancelReply: Boolean = true) {
        synchronized(speechState) {
            speechEpoch++
            TextToSpeech.stopPlayback()
            if (cancelReply) replyJob?.cancel()
            while (speech.tryReceive().isSuccess) speechLeft.decrementAndGet()
            listenRequest = null
            speaking = false
            _phase.value = PHASE_WAKE
        }
    }

    private fun interruptSpeech() {
        val start = android.os.SystemClock.elapsedRealtime()
        shutUp()
        JarvisLog.i("barge-in: playback stopped in ${android.os.SystemClock.elapsedRealtime() - start} ms")
        // Clear an unanswered confirmation before accepting another command, using the existing cancel route.
        val previous = pendingCancel
        pendingCancel = lifecycleScope.launch(Dispatchers.Default) {
            previous?.join()
            assistant.handle(Assistant.Input("cancel", source = "barge_in"))
        }
    }

    /** Something Jimi says on its own (reminder, low battery). Not a conversation turn. */
    private fun announce(text: String) {
        val epoch = speechEpoch
        _reply.value = text
        Notifications.showReply(this, "", text)
        lifecycleScope.launch {
            if (epoch != speechEpoch) return@launch
            speaking = true
            say(text, epoch)
            awaitSpeech()
            delay(TAIL_MS)
            synchronized(speechState) {
                if (epoch == speechEpoch) speaking = false
            }
        }
    }

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 /
                intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            if (plugged || level > 20) { batteryWarnedAt = 100; return }
            val step = if (level <= 5) 5 else 15
            if (level <= step && batteryWarnedAt > step) {
                batteryWarnedAt = step
                announce("${Persona.ADDRESS}, battery is at $level percent. Please charge me.")
            }
        }
    }

    /** Runs one command through the assistant and speaks the answer. Then maybe listens for a follow-up. */
    private fun ask(text: String, raw: String, source: String, wakeId: Long?, audioPath: String?, audio: ShortArray?, refine: (suspend () -> String)? = null) = synchronized(speechState) {
        phraseWarm?.cancel()
        shutUp()
        var epoch = speechEpoch
        agentInterrupt.set(null)
        speaking = true
        _phase.value = PHASE_THINKING
        thinkingCue.start(epoch, SystemClock.elapsedRealtime())
        _heard.value = text
        _reply.value = ""
        val asked = SystemClock.elapsedRealtime()
        commandStartedAt = asked
        timedCommandEpoch = epoch
        firstReplyReadyAt = 0L
        replyJob = lifecycleScope.launch(Dispatchers.Default) {
            var next: String? = null
            try {
                pendingCancel?.join()
                if (epoch != speechEpoch) return@launch
                val text = refine?.invoke() ?: text
                if (refine != null) JarvisLog.i("latency: second-pass refine ${SystemClock.elapsedRealtime() - asked} ms")
                synchronized(speechState) {
                    if (epoch != speechEpoch) return@launch
                    _heard.value = text
                }
                val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                val input = Assistant.Input(text, raw, source, locked, voiceOk(locked, audio), wakeId = wakeId, audioPath = audioPath)
                var r = try {
                    // adb test hook: source "offline_test" times the on-phone path with the cloud skipped
                    withContext(if (source == "offline_test") CloudBrain.Offline else EmptyCoroutineContext) { assistant.handle(input) { sentence ->
                        synchronized(speechState) {
                            if (epoch != speechEpoch) throw CancellationException("Speech interrupted")
                            thinkingCue.finish(epoch)
                            if (firstReplyReadyAt == 0L) firstReplyReadyAt = SystemClock.elapsedRealtime()
                            _phase.value = PHASE_SPEAKING
                            say(sentence, epoch)
                        }
                    } }
                } finally {
                    synchronized(speechState) { thinkingCue.finish(epoch) }
                }
                val interrupted = synchronized(speechState) {
                    val interruption = agentInterrupt.getAndSet(null)
                    if (interruption != null && speechEpoch == epoch + 1) epoch = speechEpoch
                    if (epoch != speechEpoch) return@launch
                    interruption
                }
                if (interrupted == "panic") {
                    r = assistant.handle(input.copy(heard = "panic", raw = "panic", source = "voice", audioPath = null))
                } else if (interrupted != null) {
                    r = r.copy(text = "Screen task stopped.", endConversation = true, expectAnswer = false)
                }
                if (r.tool == "screen_agent" || interrupted != null) showPanel()
                synchronized(speechState) {
                    if (epoch != speechEpoch) return@launch
                    if (speechLeft.get() == 0 && r.spokenAlready) _reply.value = r.text
                    _turnId.value = r.turnId
                    if (firstReplyReadyAt == 0L) firstReplyReadyAt = SystemClock.elapsedRealtime()
                    _phase.value = PHASE_SPEAKING
                }
                JarvisLog.i("reply (${r.tool ?: "talk"}): ${r.text.length} characters, ${SystemClock.elapsedRealtime() - asked} ms after command")
                if (!r.spokenAlready && r.tool != "panic") say(r.text, epoch)
                Notifications.showReply(this@JarvisCoreService, text, r.text)
                if (r.tool == "panic") synchronized(speechState) {
                    if (epoch != speechEpoch) return@launch
                    shutUp(cancelReply = false)
                    epoch = speechEpoch
                }
                if (!r.spokenAlready && r.tool == "panic") say(r.text, epoch)
                awaitSpeech()
                synchronized(speechState) {
                    if (epoch == speechEpoch) {
                        _reply.value = r.text
                        _lastTurn.value = Turn(r.turnId, text, r.text, r.tool)
                    }
                }
                if (r.expectAnswer || (followUp && !r.endConversation && source != "typed")) next = "follow_up"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                agentInterrupt.set(null)
                JarvisLog.e("command failed", t)
            } finally {
                synchronized(speechState) { thinkingCue.finish(epoch) }
                delay(TAIL_MS)   // let the speaker go quiet before the mic listens again
                synchronized(speechState) {
                    if (epoch == speechEpoch) {
                        listenRequest = next
                        if (next == null) _phase.value = if (_microphoneAvailable.value) PHASE_WAKE else PHASE_MIC_BUSY
                        speaking = false
                    }
                }
            }
        }
    }

    /**
     * Voice ID (PLAN 12.2): only checked while locked with the setting on. No audio, no model or no enrolment
     * counts as "not the owner" then, so risky tools are refused rather than allowed.
     */
    private fun voiceOk(locked: Boolean, audio: ShortArray?): Boolean? {
        if (!locked || !voiceId) return null
        val score = audio?.takeIf { it.isNotEmpty() }?.let { runCatching { speaker.score(it) }.getOrNull() }
        JarvisLog.i("voice ID score ${score?.let { "%.2f".format(it) } ?: "none"} (need ${"%.2f".format(speakerThreshold)})")
        _lastVoiceScore.value = score
        return score != null && score >= speakerThreshold
    }

    private fun showPanel() {
        if (!showPanel || screenAgent.running) return
        runCatching {
            startActivity(
                Intent(this, AssistantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }.onFailure { JarvisLog.e("panel not shown (allow 'display over other apps')", it) }
    }

    private fun startListening() {
        lifecycleScope.launch { settings.wakeThreshold.collect { threshold = it } }
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:listen")
            .apply { acquire() }
        listenJob = lifecycleScope.launch(Dispatchers.Default) {
            try {
                listenLoop()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                JarvisLog.e("listening stopped", t)
                Notifications.showResumeListening(this@JarvisCoreService)
            } finally {
                _running.value = false
                wakeLock?.takeIf { it.isHeld }?.release()
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
        val vocabulary = SttVocabularyLoader(this).load()
        val currentVocabulary = AtomicReference(vocabulary)
        val vocabularyJob = lifecycleScope.launch(Dispatchers.IO) {
            SttVocabularyUpdates(this@JarvisCoreService).snapshots().collect { currentVocabulary.set(it) }
        }
        var corrector = SttCorrector(vocabulary.contacts, vocabulary.apps)
        val engineSettings = SpeechEngineSettings(this)
        val phone = PhoneSpeechRecognizer(this)
        val capturePolicy = SpeechCapturePolicy()
        val secondPass = SecondPassSpeechToText(this)
        val stt = runCatching { SpeechToText(this, hotwords = vocabulary.hotwords) }
            .onSuccess { JarvisLog.i("speech-to-text: ${it.modelName}") }
            .onFailure { JarvisLog.e("speech-to-text not loaded (run tools/fetch_speech.sh)", it) }
            .getOrNull()
        var command: SpeechToText.Session? = null
        var commandEpoch = speechEpoch
        var interruption: SpeechToText.Session? = null
        var interruptionChunks = 0
        var stopCommand: SpeechToText.Session? = null
        var stopChunks = 0
        val stopListener = ScreenAgentInterrupt()
        var commandSource = "voice"
        val commandAudio = ArrayList<ShortArray>()
        var wasBusy = false
        var wasSpeaking = false
        var enrollAudio: ArrayList<ShortArray>? = null
        val chunk = ShortArray(WakeWordDetector.CHUNK)
        var ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
        val rec = SharedMicrophone(this, WakeWordDetector.SAMPLE_RATE) { available ->
            _microphoneAvailable.value = available
            if (!speaking) _phase.value = if (available) PHASE_WAKE else PHASE_MIC_BUSY
            Notifications.updateListening(this, if (available) PHASE_WAKE else PHASE_MIC_BUSY)
        }
        val tone = Chimes()
        var clips = ClipCollector()
        var interrupted = false
        suspend fun phoneCapture(source: String, stripWake: Boolean, epoch: Int): Boolean {
            if (capturePolicy.select(engineSettings.engine.first(), phone.isOnline()) != SpeechEngine.PHONE_ON_DEVICE) {
                val snapshot = currentVocabulary.get()
                corrector = SttCorrector(snapshot.contacts, snapshot.apps)
                JarvisLog.i("STT engine=Jimi offline capture=$source")
                return false
            }
            val snapshot = currentVocabulary.get()
            val commandCorrector = SttCorrector(snapshot.contacts, snapshot.apps)
            interruption?.close()
            interruption = null
            stopCommand?.close()
            stopCommand = null
            rec.releaseForPhone()
            _microphoneAvailable.value = false
            _phase.value = PHASE_PREPARING
            JarvisLog.i("STT engine=Phone capture=$source; sherpa microphone released")
            try {
                val raw = coroutineScope {
                    val recognition = async { phone.recognize(snapshot.hotwords, onReady = {
                        if (epoch == speechEpoch) {
                            _phase.value = if (source == "follow_up") PHASE_FOLLOW_UP else PHASE_HEARING
                            if (source == "voice") acknowledgeWake(tone) else tone.listen()
                        }
                    }) }
                    val cancellation = launch {
                        while (recognition.isActive) {
                            if (epoch != speechEpoch) { recognition.cancel(); break }
                            delay(100)
                        }
                    }
                    try { recognition.await() }
                    catch (cancelled: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        null
                    } finally { cancellation.cancel() }
                }
                if (epoch != speechEpoch) return true
                val normalized = raw?.let(SpeechToText::normalize).orEmpty()
                val stripped = WakePhrase.strip(normalized)
                val heard = if (source == "barge_in") BargeIn.stripInterruption(stripped) else stripped
                if (heard.isBlank()) {
                    capturePolicy.phoneFailed()
                    _heard.value = ""
                    _phase.value = PHASE_WAKE
                    JarvisLog.i("STT engine=Phone unavailable/error/blank; retry Phone when online, offline fallback otherwise")
                    return true
                }
                JarvisLog.i("STT engine=Phone transcript received (${heard.length} characters)")
                synchronized(speechState) {
                    if (epoch == speechEpoch) {
                        tone.endListening()
                        onCommand(commandCorrector.correct(heard), raw.orEmpty(), source,
                            if (source == "voice") clips.lastWakeId else null,
                            clips.lastWakePeak, null, ShortArray(0), tone)
                    }
                }
                return true
            } finally {
                ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
                detector.reset()
                _micLevel.value = 0f
                wasBusy = true
                JarvisLog.i("STT engine=Phone finished; sherpa microphone resumes on next read")
            }
        }
        try {
            _running.value = true
            JarvisLog.i("listening")
            while (currentCoroutineContext().isActive) {
                if (!rec.read(chunk)) {
                    if (interrupted) continue
                    interrupted = true
                    interruption?.close()
                    interruption = null
                    interruptionChunks = 0
                    stopCommand?.close()
                    stopCommand = null
                    stopChunks = 0
                    stopListener.reset()
                    command?.close()
                    command = null
                    commandAudio.clear()
                    if (enrollAudio != null) _enrollStatus.value = "Microphone interrupted. Please record again."
                    enrollAudio = null
                    listenRequest = null
                    _micLevel.value = 0f
                    _lastScore.value = 0f
                    _heard.value = ""
                    ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
                    clips = ClipCollector()
                    detector.reset()
                    continue
                }
                interrupted = false
                val captureEpoch = speechEpoch
                synchronized(speechState) {
                    if (thinkingCue.take(SystemClock.elapsedRealtime(), speechEpoch, _phase.value == PHASE_THINKING)) {
                        val filler = FILLERS[fillerIndex++ % FILLERS.size]
                        if (cachedAudio.containsKey(SpeechText.normalize(filler))) say(filler, speechEpoch)
                    }
                }
                if (command != null && commandEpoch != captureEpoch) {
                    command?.close()
                    command = null
                    commandAudio.clear()
                    _micLevel.value = 0f
                }
                ring.write(chunk)
                val agentRunning = screenAgent.running
                val listenForStop = stopListener.canListen(agentRunning, playbackActive, SystemClock.elapsedRealtime())
                if (!listenForStop || agentInterrupt.get() != null) {
                    stopCommand?.close()
                    stopCommand = null
                    stopChunks = 0
                }
                if (agentRunning) {
                    wasBusy = true
                    command?.close()
                    command = null
                    if (listenForStop && agentInterrupt.get() == null && stt != null) {
                        val stop = stopCommand ?: stt.session(waitStart = 3f).also { stopCommand = it }
                        stop.accept(chunk)
                        val phrase = stopListener.accept(stop.text, stop.done, SystemClock.elapsedRealtime())
                        if (phrase != null && synchronized(speechState) {
                                if (screenAgent.running && agentInterrupt.compareAndSet(null, phrase)) {
                                    shutUp(cancelReply = false)
                                    assistant.stopScreenAgent()
                                    true
                                } else false
                            }) {
                            JarvisLog.i("screen agent voice interruption: $phrase")
                        }
                        if (phrase != null || stop.done || ++stopChunks * chunk.size >= WakeWordDetector.SAMPLE_RATE * 3) {
                            stop.close()
                            stopCommand = null
                            stopChunks = 0
                            stopListener.reset()
                        }
                    }
                    continue
                }
                val cmd = command
                if (cmd != null) {
                    wasSpeaking = false
                    clips.onChunk(0f, threshold, ring, tone)   // only finishes the pending wake clip
                    cmd.accept(chunk)
                    _micLevel.value = level(chunk)
                    commandAudio.add(chunk.copyOf())
                    val heard = cmd.text.let {
                        if (commandSource == "barge_in") BargeIn.stripInterruption(it) else it
                    }
                    _heard.value = heard
                    if (cmd.done) {
                        if (heard.isNotEmpty()) tone.endListening()
                        command = null
                        _micLevel.value = 0f
                        JarvisLog.i("capture ended ($commandSource): ${cmd.finishReason}")
                        cmd.close()
                        val audio = join(commandAudio)
                        val path = saveCommand(audio, cmd.raw)
                        val wakeId = if (commandSource == "voice") clips.lastWakeId else null
                        val source = commandSource
                        synchronized(speechState) {
                            if (commandEpoch == speechEpoch) {
                                onCommand(corrector.correct(heard), cmd.raw, source, wakeId, clips.lastWakePeak, path, audio, tone) {
                                    val decoded = secondPass.refine(audio, cmd.text, settings.sttSecondPass.first(), cmd.stripWake)
                                    corrector.correct((if (source == "barge_in") BargeIn.stripInterruption(decoded) else decoded).ifBlank { heard })
                                }
                            }
                        }
                    }
                    continue
                }
                if (speaking) {
                    if (!wasSpeaking) detector.reset()
                    wasSpeaking = true
                    wasBusy = true
                    val score = detector.process(chunk)
                    _lastScore.value = score
                    val wake = score >= BargeIn.wakeThreshold(threshold) &&
                        listOf("hey jimi", "hey jimmy", "hey jarvis").none { BargeIn.isPlaybackEcho(it, playbackText) }
                    var stop = false
                    if (!wake && stt != null && BargeIn.canRecognizeStop(rec.echoCanceled, TextToSpeech.playbackEnergy)) {
                        val candidate = interruption ?: stt.session().also { interruption = it; interruptionChunks = 0 }
                        candidate.accept(chunk)
                        stop = BargeIn.isInterruption(candidate.text) && !BargeIn.isPlaybackEcho(candidate.text, playbackText)
                        if (candidate.done || ++interruptionChunks >= 25) {
                            candidate.close()
                            interruption = null
                        }
                    } else {
                        interruption?.close()
                        interruption = null
                    }
                    if ((wake || stop) && stt != null) {
                        interruptSpeech()
                        commandEpoch = speechEpoch
                        interruption?.close()
                        interruption = null
                        // Exclude loudspeaker pre-roll; capture fresh command audio after the interruption.
                        commandSource = "barge_in"
                        commandAudio.clear()
                        _heard.value = ""
                        _reply.value = ""
                        _phase.value = PHASE_HEARING
                        showPanel()
                        if (!phoneCapture(commandSource, wake, commandEpoch)) command = stt.session(stripWake = wake)
                    }
                    continue
                }
                wasSpeaking = false
                interruption?.close()
                interruption = null
                val enrolling = enrollAudio
                if (enrolling != null) {
                    enrolling.add(chunk.copyOf())
                    if (enrolling.size >= ENROLL_CHUNKS) {
                        enrollAudio = null
                        tone.done()
                        finishEnroll(join(enrolling))
                    }
                    continue
                }
                if (enrollRequested) {
                    enrollRequested = false
                    enrollAudio = ArrayList()
                    wasBusy = true
                    _enrollStatus.value = "Recording… keep talking for $ENROLL_SECONDS seconds."
                    tone.listen()
                    continue
                }
                val req = synchronized(speechState) {
                    if (stt != null && captureEpoch == speechEpoch) {
                        listenRequest.also { listenRequest = null }
                    } else null
                }
                if (req != null && stt != null) {
                    commandEpoch = captureEpoch
                    if (req == "tap") WakeFeedbackSignal.signal(this)
                    _phase.value = if (req == "follow_up") PHASE_FOLLOW_UP else PHASE_HEARING
                    if (req == "tap") showPanel()
                    _heard.value = ""
                    if (phoneCapture(req, false, commandEpoch)) continue
                    tone.listen()
                    command = stt.session(null, waitStart = if (req == "follow_up") FOLLOW_UP_WAIT else SpeechToText.WAIT_START)
                        .also { it.accept(chunk) }
                    commandSource = req
                    commandAudio.clear()
                    commandAudio.add(chunk.copyOf())
                    wasBusy = true
                    _heard.value = ""
                    continue
                }
                if (wasBusy) { wasBusy = false; detector.reset() }   // drop audio from before the command
                val score = detector.process(chunk)
                _lastScore.value = score
                if (clips.onChunk(score, threshold, ring, tone) && stt != null) {
                    commandEpoch = captureEpoch
                    _heard.value = ""
                    _reply.value = ""
                    _phase.value = PHASE_HEARING
                    showPanel()
                    if (phoneCapture("voice", true, commandEpoch)) continue
                    acknowledgeWake(tone)
                    val preRoll = ring.last(PRE_ROLL_SAMPLES)
                    command = stt.session(preRoll)
                    commandSource = "voice"
                    commandAudio.clear()
                    commandAudio.add(preRoll)
                    wasBusy = true
                }
            }
        } finally {
            vocabularyJob.cancel()
            interruption?.close()
            stopCommand?.close()
            command?.close()
            stt?.close()
            secondPass.close()
            rec.close()
            tone.close()
            detector.close()
            _running.value = false
            _microphoneAvailable.value = false
            _phase.value = PHASE_WAKE
        }
    }

    /** Keeps each command's audio + raw STT text, to test better STT models on the user's real voice. */
    private fun saveCommand(audio: ShortArray, rawText: String): String? =
        runCatching {
            val dir = File(getExternalFilesDir(null), "commands").apply { mkdirs() }
            val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val wav = File(dir, "$name.wav")
            Wav.write(wav, audio)
            File(dir, "$name.txt").writeText(rawText + "\n")
            wav.path
        }.onFailure { JarvisLog.e("could not save command audio", it) }.getOrNull()

    private fun join(parts: List<ShortArray>): ShortArray {
        val audio = ShortArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) { p.copyInto(audio, pos); pos += p.size }
        return audio
    }

    private fun finishEnroll(audio: ShortArray) {
        lifecycleScope.launch(Dispatchers.Default) {
            val ok = runCatching { speaker.enroll(audio) }.onFailure { JarvisLog.e("enrol failed", it) }.getOrDefault(false)
            _enrollStatus.value = when {
                ok -> "Saved. ${speaker.samples} recording(s) of your voice."
                !speaker.available -> "Voice ID model missing (run tools/fetch_speech.sh)."
                else -> "Didn't work, try again."
            }
        }
    }

    /** A command was heard: answer it. Empty after a follow-up means the conversation is over. */
    private fun onCommand(
        text: String, raw: String, source: String, wakeId: Long?, wakeScore: Float, audioPath: String?, audio: ShortArray,
        tone: Chimes, refine: (suspend () -> String)? = null,
    ) {
        JarvisLog.i("heard ($source): ${text.length} characters")
        if (source == "voice") _wakeId.value = wakeId
        if (wakeId != null) Notifications.showWakeCheck(this, wakeId, wakeScore, heard = text.ifEmpty { "(nothing)" })
        if (text.isEmpty()) {
            _heard.value = if (source == "follow_up") "" else AssistantActivity.NOTHING_HEARD
            tone.timeout()
            _phase.value = PHASE_WAKE
            return
        }
        ask(text, raw, source, wakeId, audioPath, audio, refine)
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
        suspend fun onChunk(score: Float, thr: Float, ring: AudioRing, tone: Chimes): Boolean {
            val now = System.currentTimeMillis()
            if (missedRequested) {
                missedRequested = false
                save(WakeEvent.VERDICT_MISSED, 0f, thr, ring.last(WakeWordDetector.SAMPLE_RATE * MISSED_SECONDS))
                return false
            }
            if (score >= thr && now - lastWakeMs > WAKE_COOLDOWN_MS) {
                lastWakeMs = now
                WakeFeedbackSignal.signal(this@JarvisCoreService)
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
        runCatching { unregisterReceiver(battery) }
        speech.close()
        shutUp()
        speechEpoch++
        voiceJob?.cancel()
        phraseWarm?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            ttsLock.withLock {
                val voice = tts
                tts = null
                runCatching { voice?.await()?.close() }.onFailure { JarvisLog.e("voice close failed", it) }
            }
        }
        wakeLock?.takeIf { it.isHeld }?.release()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val FILLERS = listOf("Let me check.", "On it.", "Hmm, one sec.")
        private val FEEDBACK_PHRASES = FILLERS + "Uh-huh."
        private val COMMON_PHRASES = PhraseCache.COMMON.map(SpeechText::normalize).toSet()
        /** -1000 = never kill; -800 keeps us above ordinary apps but still killable if the phone is desperate. */
        private const val OOM_ADJ = -800
        const val ACTION_STOP = "com.alrasin.jarvis.STOP"
        const val ACTION_MISSED = "com.alrasin.jarvis.MISSED"
        const val ACTION_SAY = "com.alrasin.jarvis.SAY"
        const val ACTION_ASK = "com.alrasin.jarvis.ASK"
        const val ACTION_LISTEN = "com.alrasin.jarvis.LISTEN"
        const val ACTION_SHUT_UP = "com.alrasin.jarvis.SHUT_UP"
        const val ACTION_ENROLL = "com.alrasin.jarvis.ENROLL"
        const val EXTRA_TEXT = "text"
        const val EXTRA_SOURCE = "source"
        private val QUIET_ACTIONS = setOf(ACTION_SAY, ACTION_ASK, ACTION_SHUT_UP)
        private const val FOLLOW_UP_WAIT = 6f     // seconds to start talking after an answer
        private const val TAIL_MS = 350L
        private const val RING_SECONDS = 4
        private const val CLIP_SAMPLES = 40_000       // 2.5 s per wake clip
        private const val MISSED_SECONDS = 3
        private const val AFTER_CHUNKS = 6           // keep ~0.5 s after the peak in the clip
        private const val WAKE_COOLDOWN_MS = 2_000L
        private const val NEAR_MIN = 0.2f
        private const val NEAR_COOLDOWN_MS = 30_000L
        private const val PRE_ROLL_SAMPLES = 16_000   // 1 s before the wake: "…jimi turn" → "turn" is kept
        private const val ENROLL_SECONDS = 8
        private const val ENROLL_CHUNKS = ENROLL_SECONDS * WakeWordDetector.SAMPLE_RATE / WakeWordDetector.CHUNK

        const val PHASE_WAKE = "Say \"Hey Jimi\""
        const val PHASE_PREPARING = "Getting ready…"
        const val PHASE_HEARING = "Listening to you…"
        const val PHASE_SPEAKING = "Speaking…"
        const val PHASE_THINKING = "Thinking…"
        const val PHASE_FOLLOW_UP = "Anything else?"
        const val PHASE_MIC_BUSY = "Microphone busy or unavailable. Jimi will retry."

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _microphoneAvailable = MutableStateFlow(false)
        val microphoneAvailable: StateFlow<Boolean> = _microphoneAvailable
        private val _modelName = MutableStateFlow("")
        val modelName: StateFlow<String> = _modelName
        private val _lastScore = MutableStateFlow(0f)
        val lastScore: StateFlow<Float> = _lastScore
        private val _phase = MutableStateFlow(PHASE_WAKE)
        val phase: StateFlow<String> = _phase
        private val _micLevel = MutableStateFlow(0f)
        /** Loudness 0..1 while hearing a command (panel animation). */
        val micLevel: StateFlow<Float> = _micLevel

        /** RMS of a chunk mapped to 0..1 on a dB scale (-50 dB → 0, -10 dB → 1). */
        fun level(chunk: ShortArray): Float {
            var sum = 0.0
            for (s in chunk) sum += s.toDouble() * s
            val rms = kotlin.math.sqrt(sum / chunk.size.coerceAtLeast(1)) / 32768.0
            val db = 20 * kotlin.math.log10(rms.coerceAtLeast(1e-6))
            return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        }

        private val _heard = MutableStateFlow("")
        /** Live (partial) text while hearing a command, then the final text. */
        val heard: StateFlow<String> = _heard
        private val _reply = MutableStateFlow("")
        val reply: StateFlow<String> = _reply
        private val _turnId = MutableStateFlow(0L)
        /** conversation_turn id of the last answer (for ✓ / ✗ in the panel). */
        val turnId: StateFlow<Long> = _turnId
        private val _lastTurn = MutableStateFlow<Turn?>(null)
        /** The last finished turn, all fields from the same moment (panel chat bubbles). */
        val lastTurn: StateFlow<Turn?> = _lastTurn
        private val _wakeId = MutableStateFlow<Long?>(null)
        /** wake_event id of the last voice wake (for "I didn't call"). */
        val wakeId: StateFlow<Long?> = _wakeId
        private val _enrollStatus = MutableStateFlow("")
        val enrollStatus: StateFlow<String> = _enrollStatus
        private val _lastVoiceScore = MutableStateFlow<Float?>(null)
        /** Voice ID score of the last locked command (to tune the threshold). */
        val lastVoiceScore: StateFlow<Float?> = _lastVoiceScore

        fun start(context: Context) = startSafely(context, Intent(context, JarvisCoreService::class.java))

        private fun startSafely(context: Context, intent: Intent) = try {
            context.startForegroundService(intent)
        } catch (failure: RuntimeException) {
            JarvisLog.e("Jimi needs to be opened before listening can resume", failure)
            Notifications.showResumeListening(context)
            null
        }

        /** Save the last 3 s as a "missed" clip (wake word was said but not detected). */
        fun missed(context: Context) = context.startService(
            Intent(context, JarvisCoreService::class.java).setAction(ACTION_MISSED)
        )

        private fun send(context: Context, action: String, text: String? = null, source: String? = null) =
            startSafely(context,
                Intent(context, JarvisCoreService::class.java).setAction(action)
                    .putExtra(EXTRA_TEXT, text).putExtra(EXTRA_SOURCE, source),
            )

        /** Jimi says [text] out loud (reminders, alerts). */
        fun say(context: Context, text: String) = send(context, ACTION_SAY, text)

        /** Runs typed / corrected text as a command. [source]: typed | fix. */
        fun ask(context: Context, text: String, source: String = "typed") = send(context, ACTION_ASK, text, source)

        /** Start listening now, no wake word (mic button). */
        fun listen(context: Context) = send(context, ACTION_LISTEN)

        fun shutUp(context: Context) = send(context, ACTION_SHUT_UP)

        /** Record the owner's voice for voice ID (Settings tab). */
        fun enroll(context: Context) = send(context, ACTION_ENROLL)

        fun stop(context: Context) = context.startService(
            Intent(context, JarvisCoreService::class.java).setAction(ACTION_STOP)
        )
    }
}

/** One finished command: what was heard and what Jimi answered. */
data class Turn(val id: Long, val heard: String, val reply: String, val tool: String?)

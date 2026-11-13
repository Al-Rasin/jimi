package com.alrasin.jarvis.service

import com.alrasin.jarvis.brain.ContextModes
import com.alrasin.jarvis.core.ContextNow
import com.alrasin.jarvis.agent.appmap.AppMapFreshness
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alrasin.jarvis.agent.ScreenAgentSession
import com.alrasin.jarvis.actions.ToolRegistry
import com.alrasin.jarvis.brain.Assistant
import com.alrasin.jarvis.brain.CloudBrain
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.ui.AssistantActivity
import com.alrasin.jarvis.ui.Backdrop
import com.alrasin.jarvis.voice.AudioRing
import com.alrasin.jarvis.voice.BanglaSpeech
import com.alrasin.jarvis.voice.BanglaSpeechToText
import com.alrasin.jarvis.voice.NoiseCleaner
import com.alrasin.jarvis.voice.PortableModels
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
import com.alrasin.jarvis.voice.Chimes
import com.alrasin.jarvis.voice.PhraseCache
import com.alrasin.jarvis.knowledge.SharedEmbedder
import com.alrasin.jarvis.voice.SpeechText
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.WakeFeedback as WakeFeedbackSignal
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import com.alrasin.jarvis.service.CoreState._running
import com.alrasin.jarvis.service.CoreState._microphoneAvailable
import com.alrasin.jarvis.service.CoreState._modelName
import com.alrasin.jarvis.service.CoreState._lastScore
import com.alrasin.jarvis.service.CoreState._phase
import com.alrasin.jarvis.service.CoreState._micLevel
import com.alrasin.jarvis.service.CoreState._heard
import com.alrasin.jarvis.service.CoreState._reply
import com.alrasin.jarvis.service.CoreState._turnId
import com.alrasin.jarvis.service.CoreState._lastTurn
import com.alrasin.jarvis.service.CoreState._wakeId
import com.alrasin.jarvis.service.CoreState._enrollStatus
import com.alrasin.jarvis.service.CoreState._lastVoiceScore
import com.alrasin.jarvis.service.CoreState.PHASE_WAKE
import com.alrasin.jarvis.service.CoreState.PHASE_PREPARING
import com.alrasin.jarvis.service.CoreState.PHASE_HEARING
import com.alrasin.jarvis.service.CoreState.PHASE_SPEAKING
import com.alrasin.jarvis.service.CoreState.PHASE_THINKING
import com.alrasin.jarvis.service.CoreState.PHASE_FOLLOW_UP
import com.alrasin.jarvis.service.CoreState.PHASE_MIC_BUSY
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
    @Inject lateinit var notifications: NotificationDao
    @Inject lateinit var assistant: Assistant
    @Inject lateinit var models: ModelBrain
    @Inject lateinit var cloud: CloudBrain
    @Inject lateinit var registry: ToolRegistry
    @Inject lateinit var speaker: SpeakerVerifier
    @Inject lateinit var screenAgent: ScreenAgentSession
    @Inject lateinit var mapFreshness: AppMapFreshness

    @Volatile private var threshold = Settings.DEFAULT_THRESHOLD
    @Volatile private var banglaOn = false
    @Volatile private var denoiseOn = false
    @Volatile private var missedRequested = false
    /** While busy, only echo-aware interruption detection consumes microphone audio. */
    @Volatile private var speaking = false
    @Volatile private var playbackActive = false
    private val agentInterrupt = AtomicReference<String?>(null)
    /** Set to "follow_up" or "tap": the mic loop opens a command session without the wake word. */
    @Volatile private var listenRequest: String? = null
    @Volatile private var spokenWake = true
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
    private var voiceJob: Job? = null
    private val voice by lazy {
        VoiceBox(this, lifecycleScope) {
            !speaking && speechLeft.get() == 0 && listenRequest == null && _phase.value == PHASE_WAKE
        }
    }
    @Volatile private var speechEpoch = 0
    private val speechState = Any()
    private val thinkingCue = ThinkingCue()
    @Volatile private var replyJob: Job? = null
    @Volatile private var pendingCancel: Job? = null
    @Volatile private var playbackText = ""
    private val speech = Channel<Pair<Int, String>>(Channel.UNLIMITED)
    private val speechLeft = AtomicInteger(0)
    private var listenJob: Job? = null
    private val alerts by lazy {
        PhoneAlerts(
            this, lifecycleScope, { settings.listeningEnabled.first() }, { registry.contacts.nameFor(it) },
            proactive = { settings.proactive.first() }, importantSince = { since -> notifications.since(since).filter { it.important } },
        ) { announce(it) }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private val contextWatch by lazy { ContextWatch(this, lifecycleScope, settings) }

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
        if (intent?.action != ACTION_STOP) lifecycleScope.launch(Dispatchers.IO) {
            runCatching { registry.profile.refreshIfOld() }.onFailure { JarvisLog.e("phone scan failed", it) }   // D-049
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
        lifecycleScope.launch(Dispatchers.IO) { voice.loadWakeClip() }
        voiceJob = lifecycleScope.launch(Dispatchers.IO) {
            settings.voice.distinctUntilChanged().collect { id -> voice.switchVoice(id) }
        }
        lifecycleScope.launch(Dispatchers.IO) { settings.voiceSpeed.distinctUntilChanged().collect { voice.setSpeed(it) } }
        lifecycleScope.launch(Dispatchers.IO) { settings.onlineVoice.distinctUntilChanged().collect { voice.setOnline(it) } }
        // a few pieces made ahead, so a slow piece does not leave the speaker silent
        val audio = Channel<Triple<Int, String, PhraseCache.Audio>>(AUDIO_AHEAD)
        lifecycleScope.launch(Dispatchers.IO) {
            for ((epoch, s) in speech) {
                val made = try {
                    voice.make(s) { epoch == speechEpoch }
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
                        TextToSpeech.playPcm(a.samples, a.sampleRate, SpeechText.pauseAfterMs(text), onStarted = {
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
        alerts.register()
        contextWatch.start()
        mapFreshness.start()
        lifecycleScope.launch(Dispatchers.IO) { ProcessGuard.run(this@JarvisCoreService) }
    }

    private fun acknowledgeWake(tone: Chimes) {
        warmUpForCommand()
        val audio = voice.wakeAudio()
        if (!spokenWake || audio == null) { tone.wake(); return }
        val epoch = speechEpoch
        lifecycleScope.launch(Dispatchers.IO) {
            TextToSpeech.playPcm(audio.samples, audio.sampleRate) { epoch != speechEpoch }
        }
    }

    private fun warmUpForCommand() {
        // While the user talks: open the cloud connection, or load the local tool model when the cloud is not usable.
        lifecycleScope.launch(Dispatchers.IO) { if (cloud.enabled()) cloud.warm() else models.preloadTools() }
        // Memory/wiki ranking model (small, on-device); loads once, no-op when missing.
        lifecycleScope.launch(Dispatchers.IO) { SharedEmbedder.get(this@JarvisCoreService) }
    }

    /**
     * Spoken wake reply played to the end before Google's recognizer starts. Played while Google listens, the reply is
     * taken as the user's speech: the echo canceler wipes it, Google ends with "no match" before the real command.
     */
    private suspend fun playWakeReplyFirst(): Boolean {
        val audio = voice.wakeAudio()
        if (!spokenWake || audio == null) return false
        warmUpForCommand()
        val epoch = speechEpoch
        withContext(Dispatchers.IO) { TextToSpeech.playPcm(audio.samples, audio.sampleRate) { epoch != speechEpoch } }
        return true
    }

    private fun say(text: String, epoch: Int = speechEpoch) {
        // Bangla (a chat line): one piece, the voice splits Bangla from English itself; chunks() would cut the Bangla out.
        val chunks = if (SpeechText.hasBangla(text)) listOf(text)
            else if (voice.isCached(text)) listOf(SpeechText.normalize(text))
            else {
                // Each piece gets its own tune, so short pieces sound broken. Only the reply's first piece is short (fast start).
                val max = if (voice.phoneEngine) PHONE_VOICE_WORDS else VOICE_WORDS
                val replyStart = epoch == timedCommandEpoch && firstAudioEpoch != epoch && speechLeft.get() == 0
                SpeechText.chunks(text, maxWords = max, firstWords = if (!replyStart) max else if (voice.phoneEngine) 3 else 2)
            }
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

    /** Runs one command through the assistant and speaks the answer. Then maybe listens for a follow-up. */
    private fun ask(text: String, raw: String, source: String, wakeId: Long?, audioPath: String?, audio: ShortArray?, refine: (suspend () -> String)? = null) = synchronized(speechState) {
        voice.stopWarming()
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
                if (text.isBlank()) {   // Bangla mode tried a blank English command and heard nothing either
                    synchronized(speechState) {
                        if (epoch == speechEpoch) _heard.value = if (source == "follow_up") "" else AssistantActivity.NOTHING_HEARD
                    }
                    return@launch
                }
                synchronized(speechState) {
                    if (epoch != speechEpoch) return@launch
                    _heard.value = text
                }
                val locked = isLocked()
                val input = Assistant.Input(text, raw, source, locked, voiceOk(locked, audio), wakeId = wakeId, audioPath = audioPath)
                // car / headphones mode: speak only the first sentences; the full answer stays in the notification
                val short = ContextNow.mode.shortAnswers
                var spokenSentences = 0
                var r = try {
                    // adb test hook: source "offline_test" times the on-phone path with the cloud skipped
                    withContext(if (source == "offline_test") CloudBrain.Offline else EmptyCoroutineContext) { assistant.handle(input) { sentence ->
                        synchronized(speechState) {
                            if (epoch != speechEpoch) throw CancellationException("Speech interrupted")
                            thinkingCue.finish(epoch)
                            if (firstReplyReadyAt == 0L) firstReplyReadyAt = SystemClock.elapsedRealtime()
                            _phase.value = PHASE_SPEAKING
                            if (!short || spokenSentences++ < ContextModes.SHORT_SENTENCES) say(sentence, epoch)
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
                if (r.text.isNotBlank()) {   // blank = follow-up noise dropped quietly
                    if (!r.spokenAlready && r.tool != "panic") say(if (short) ContextModes.shorten(r.text) else r.text, epoch)
                    Notifications.showReply(this@JarvisCoreService, text, r.text)
                }
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
        lifecycleScope.launch {
            // the blurred screen must be taken before the panel covers it
            if (!AssistantActivity.isOpen) Backdrop.capture()
            runCatching {
                startActivity(
                    Intent(this@JarvisCoreService, AssistantActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
            }.onFailure { JarvisLog.e("panel not shown (allow 'display over other apps')", it) }
        }
    }

    private fun startListening() {
        lifecycleScope.launch { settings.wakeThreshold.collect { threshold = it } }
        lifecycleScope.launch { settings.sttBangla.collect { banglaOn = it } }
        lifecycleScope.launch { settings.sttDenoise.collect { denoiseOn = it } }
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
            SttVocabularyUpdates(this@JarvisCoreService).snapshots().collect {
                val appsChanged = it.apps != currentVocabulary.get().apps
                currentVocabulary.set(it)
                // App installed or removed: learn its abilities now, not tomorrow (D-056, G.1).
                if (appsChanged) runCatching { registry.profile.scan() }.onFailure { e -> JarvisLog.e("phone scan failed", e) }
            }
        }
        var corrector = SttCorrector(vocabulary.contacts, vocabulary.apps)
        val engineSettings = SpeechEngineSettings(this)
        val phone = PhoneSpeechRecognizer(this)
        val capturePolicy = SpeechCapturePolicy()
        val secondPass = SecondPassSpeechToText(this)
        val bangla = BanglaSpeechToText(this)
        val cleaner = NoiseCleaner(this)
        val stt = runCatching { SpeechToText(this, hotwords = vocabulary.hotwords) }
            .onSuccess { JarvisLog.i("speech-to-text: ${it.modelName}") }
            .onFailure { JarvisLog.e("speech-to-text not loaded (run tools/fetch_speech.sh)", it) }
            .getOrNull()
        var command: SpeechToText.Session? = null
        var commandEpoch = speechEpoch
        val interruption = stt?.let(::SpeechStopListener)
        val stopListener = AgentStopListener(stt)
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
        var clips = newClips()
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
            stopListener.close()
            rec.releaseForPhone()
            _microphoneAvailable.value = false
            _phase.value = PHASE_PREPARING
            JarvisLog.i("STT engine=Phone capture=$source; sherpa microphone released")
            try {
                val replied = source == "voice" && playWakeReplyFirst()
                if (epoch != speechEpoch) return true
                val raw = coroutineScope {
                    val recognition = async { phone.recognize(snapshot.hotwords, onReady = {
                        if (epoch == speechEpoch) {
                            _phase.value = if (source == "follow_up") PHASE_FOLLOW_UP else PHASE_HEARING
                            if (source != "voice") tone.listen() else if (!replied) acknowledgeWake(tone)
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
                if (heard.isBlank() && source == "follow_up" && WakePhrase.isOnlyWake(normalized)) {
                    JarvisLog.i("follow-up heard only the wake phrase; listening for the command")
                    listenRequest = "tap"
                    return true
                }
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
                    clips = newClips()
                    detector.reset()
                    continue
                }
                interrupted = false
                val captureEpoch = speechEpoch
                synchronized(speechState) {
                    if (thinkingCue.take(SystemClock.elapsedRealtime(), speechEpoch, _phase.value == PHASE_THINKING)) {
                        val filler = FILLERS[fillerIndex++ % FILLERS.size]
                        if (voice.isCached(filler)) say(filler, speechEpoch)
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
                if (!listenForStop || agentInterrupt.get() != null) stopListener.close()
                if (agentRunning) {
                    wasBusy = true
                    command?.close()
                    command = null
                    if (listenForStop && agentInterrupt.get() == null) {
                        val phrase = stopListener.accept(chunk, SystemClock.elapsedRealtime())
                        if (phrase != null && synchronized(speechState) {
                                if (screenAgent.running && agentInterrupt.compareAndSet(null, phrase)) {
                                    shutUp(cancelReply = false)
                                    assistant.stopScreenAgent()
                                    true
                                } else false
                            }) {
                            JarvisLog.i("screen agent voice interruption: $phrase")
                        }
                    }
                    continue
                }
                val cmd = command
                if (cmd != null) {
                    wasSpeaking = false
                    clips.onChunk(0f, threshold, ring, tone)   // only finishes the pending wake clip
                    val heardChunk = cleaner.clean(chunk, denoiseOn)
                    cmd.accept(heardChunk)
                    _micLevel.value = CommandAudio.level(chunk)
                    commandAudio.add(heardChunk)
                    val heard = cmd.text.let {
                        if (commandSource == "barge_in") BargeIn.stripInterruption(it) else it
                    }
                    _heard.value = heard
                    if (cmd.done && heard.isEmpty() && commandSource == "follow_up" && WakePhrase.isOnlyWake(cmd.raw)) {
                        // "Hey Jimi" said during the follow-up window: start a fresh command capture.
                        JarvisLog.i("follow-up heard only the wake phrase; listening for the command")
                        cmd.close()
                        tone.listen()
                        cleaner.reset()
                        commandAudio.clear()
                        _phase.value = PHASE_HEARING
                        command = stt?.session(stripWake = true)
                        commandSource = "tap"
                        continue
                    }
                    if (cmd.done) {
                        if (heard.isNotEmpty()) tone.endListening()
                        command = null
                        _micLevel.value = 0f
                        JarvisLog.i("capture ended ($commandSource): ${cmd.finishReason}")
                        cmd.close()
                        val audio = CommandAudio.join(commandAudio)
                        val path = CommandAudio.save(this, audio, cmd.raw)
                        val wakeId = if (commandSource == "voice") clips.lastWakeId else null
                        val source = commandSource
                        synchronized(speechState) {
                            if (commandEpoch == speechEpoch) {
                                // Pure Bangla often gives blank English text; let Whisper try before "nothing heard".
                                val banglaMayHear = BanglaSpeech.use(banglaOn, PortableModels.banglaReady(this), audio.size)
                                onCommand(corrector.correct(heard), cmd.raw, source, wakeId, clips.lastWakePeak, path, audio, tone, banglaMayHear) {
                                    val decoded = if (settings.sttBangla.first()) bangla.hear(audio, cmd.text, true, cmd.stripWake)
                                        else secondPass.refine(audio, cmd.text, settings.sttSecondPass.first(), cmd.stripWake)
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
                    if (!wake && interruption != null && BargeIn.canRecognizeStop(rec.echoCanceled, TextToSpeech.playbackEnergy)) {
                        stop = interruption.accept(chunk, playbackText)
                    } else {
                        interruption?.close()
                    }
                    if ((wake || stop) && stt != null) {
                        interruptSpeech()
                        commandEpoch = speechEpoch
                        interruption?.close()
                        // Exclude loudspeaker pre-roll; capture fresh command audio after the interruption.
                        commandSource = "barge_in"
                        commandAudio.clear()
                        _heard.value = ""
                        _reply.value = ""
                        _phase.value = PHASE_HEARING
                        showPanel()
                        cleaner.reset()
                        if (!phoneCapture(commandSource, wake, commandEpoch)) command = stt.session(stripWake = wake)
                    }
                    continue
                }
                wasSpeaking = false
                interruption?.close()
                val enrolling = enrollAudio
                if (enrolling != null) {
                    enrolling.add(chunk.copyOf())
                    if (enrolling.size >= ENROLL_CHUNKS) {
                        enrollAudio = null
                        tone.done()
                        finishEnroll(CommandAudio.join(enrolling))
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
                    cleaner.reset()
                    val firstChunk = cleaner.clean(chunk, denoiseOn)
                    command = stt.session(null, waitStart = if (req == "follow_up") FOLLOW_UP_WAIT else SpeechToText.WAIT_START, stripWake = req == "follow_up")
                        .also { it.accept(firstChunk) }
                    commandSource = req
                    commandAudio.clear()
                    commandAudio.add(firstChunk)
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
                    cleaner.reset()
                    val preRoll = cleaner.clean(ring.last(PRE_ROLL_SAMPLES), denoiseOn)
                    command = stt.session(preRoll, stripWake = true)
                    commandSource = "voice"
                    commandAudio.clear()
                    commandAudio.add(preRoll)
                    wasBusy = true
                }
            }
        } finally {
            vocabularyJob.cancel()
            interruption?.close()
            stopListener.close()
            command?.close()
            stt?.close()
            secondPass.close()
            bangla.close()
            cleaner.close()
            rec.close()
            tone.close()
            detector.close()
            _running.value = false
            _microphoneAvailable.value = false
            _phase.value = PHASE_WAKE
        }
    }

    private fun newClips() = ClipCollector(this, dao) { missedRequested.also { missedRequested = false } }

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
        tone: Chimes, refineEmpty: Boolean = false, refine: (suspend () -> String)? = null,
    ) {
        JarvisLog.i("heard ($source): ${text.length} characters")
        if (source == "voice") _wakeId.value = wakeId
        if (wakeId != null) Notifications.showWakeCheck(this, wakeId, wakeScore, heard = text.ifEmpty { "(nothing)" })
        if (text.isEmpty() && !(refineEmpty && refine != null)) {
            _heard.value = if (source == "follow_up") "" else AssistantActivity.NOTHING_HEARD
            tone.timeout()
            _phase.value = PHASE_WAKE
            return
        }
        ask(text, raw, source, wakeId, audioPath, audio, refine)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onDestroy() {
        listenJob?.cancel()
        alerts.unregister()
        contextWatch.stop()
        mapFreshness.stop()
        speech.close()
        shutUp()
        speechEpoch++
        voiceJob?.cancel()
        voice.close()
        wakeLock?.takeIf { it.isHeld }?.release()
        _running.value = false
        super.onDestroy()
    }

    companion object {
        private val FILLERS = VoiceBox.FILLERS
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
        /** Most words per spoken piece: Google voice makes ~25 words in ~1 s; Piper ~10 words in ~2 s (3 s of speech). */
        private const val PHONE_VOICE_WORDS = 25
        private const val VOICE_WORDS = 10
        private const val AUDIO_AHEAD = 3
        private const val PRE_ROLL_SAMPLES = 16_000   // 1 s before the wake: "…jimi turn" → "turn" is kept
        private const val ENROLL_SECONDS = 8
        private const val ENROLL_CHUNKS = ENROLL_SECONDS * WakeWordDetector.SAMPLE_RATE / WakeWordDetector.CHUNK

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

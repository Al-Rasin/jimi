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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.BatteryManager
import android.os.PowerManager
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.brain.Assistant
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.WakeEvent
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.ui.AssistantActivity
import com.alrasin.jarvis.voice.AudioRing
import com.alrasin.jarvis.voice.BargeIn
import com.alrasin.jarvis.voice.SpeakerVerifier
import com.alrasin.jarvis.voice.SpeechToText
import com.alrasin.jarvis.voice.SttCorrector
import com.alrasin.jarvis.voice.SttVocabularyLoader
import com.alrasin.jarvis.voice.Chimes
import com.alrasin.jarvis.voice.PhraseCache
import com.alrasin.jarvis.voice.TextToSpeech
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
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

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
    @Inject lateinit var speaker: SpeakerVerifier

    @Volatile private var threshold = Settings.DEFAULT_THRESHOLD
    @Volatile private var missedRequested = false
    /** While busy, only echo-aware interruption detection consumes microphone audio. */
    @Volatile private var speaking = false
    /** Set to "follow_up" or "tap": the mic loop opens a command session without the wake word. */
    @Volatile private var listenRequest: String? = null
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
            ACTION_LISTEN -> listenRequest = "tap"
            ACTION_SHUT_UP -> shutUp()
            ACTION_ENROLL -> enrollRequested = true
        }
        startForeground(
            Notifications.ID_SERVICE,
            Notifications.listening(this, "Say \"Hey Jimi\""),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        ensureVoice()
        val on = intent?.action !in QUIET_ACTIONS || runBlocking { settings.listeningEnabled.first() }
        if (listenJob == null && on) {
            startListening()
            BootReceiver.schedule(this)
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
        voiceJob = lifecycleScope.launch(Dispatchers.IO) {
            settings.voice.distinctUntilChanged().collect { id -> switchVoice(id) }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            settings.voiceSpeed.distinctUntilChanged().collect { s ->
                ttsLock.withLock {
                    voiceSpeed = s
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
                    ttsLock.withLock {
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
                        TextToSpeech.playPcm(a.samples, a.sampleRate) { epoch != speechEpoch }
                    }
                } catch (t: Throwable) {
                    JarvisLog.e("play failed", t)
                } finally {
                    playbackText = ""
                    speechLeft.decrementAndGet()
                }
            }
        }
        lifecycleScope.launch { settings.followUp.collect { followUp = it } }
        lifecycleScope.launch { settings.showPanel.collect { showPanel = it } }
        lifecycleScope.launch { settings.voiceId.collect { voiceId = it } }
        lifecycleScope.launch { settings.speakerThreshold.collect { speakerThreshold = it } }
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        models.warmUp()
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
        val common = text.trim() in COMMON_PHRASES
        var cache = if (common) phrases?.takeIf { it.voiceId == v.voice.id && it.speed == v.speed } else null
        cache?.get(text)?.let { return it }
        val made = v.generate(text)
        if (common && cache == null) {
            cache = PhraseCache(File(cacheDir, "phrases"), v.voice.id, v.speed, made.sampleRate)
            phrases = cache
        }
        cache?.put(text, made.samples, made.sampleRate)
        return PhraseCache.Audio(made.samples, made.sampleRate)
    }

    /** Makes the common replies in the background, one at a time, only while Jimi is not speaking. */
    private fun warmPhrases(v: TextToSpeech) {
        phraseWarm?.cancel()
        val speed = v.speed
        phraseWarm = lifecycleScope.launch(Dispatchers.IO) {
            for (text in PhraseCache.COMMON) {
                while (isActive) {
                    delay(500)
                    val done = ttsLock.withLock {
                        if (readyVoice() !== v || v.speed != speed) return@launch
                        if (speaking || speechLeft.get() > 0 || listenRequest != null) false
                        else {
                            runCatching { audioFor(v, text) }
                                .onFailure { JarvisLog.e("phrase warm failed", it) }
                                .getOrNull() ?: return@launch
                            true
                        }
                    }
                    if (done) break
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun readyVoice(): TextToSpeech? = tts?.takeIf { it.isCompleted }?.getCompleted()

    private fun say(text: String, epoch: Int = speechEpoch) {
        synchronized(speechState) {
            if (text.isBlank() || epoch != speechEpoch) return
            speechLeft.incrementAndGet()
            if (speech.trySend(epoch to text).isFailure) speechLeft.decrementAndGet()
        }
    }

    private suspend fun awaitSpeech() {
        while (speechLeft.get() > 0) delay(50)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun shutUp() {
        synchronized(speechState) {
            speechEpoch++
            TextToSpeech.stopPlayback()
            replyJob?.cancel()
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
    private fun ask(text: String, raw: String, source: String, wakeId: Long?, audioPath: String?, audio: ShortArray?) {
        shutUp()
        val epoch = speechEpoch
        speaking = true
        _phase.value = PHASE_THINKING
        _heard.value = text
        _reply.value = ""
        replyJob = lifecycleScope.launch(Dispatchers.Default) {
            var next: String? = null
            try {
                pendingCancel?.join()
                if (epoch != speechEpoch) return@launch
                val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
                val input = Assistant.Input(text, raw, source, locked, voiceOk(locked, audio), wakeId = wakeId, audioPath = audioPath)
                var started = false
                // slow answer (model loading, web): say something so the user knows Jimi heard (PLAN 9.4)
                val filler = launch { delay(FILLER_MS); if (!started) say(Persona.FILLER, epoch) }
                val r = try {
                    assistant.handle(input) { sentence ->
                        synchronized(speechState) {
                            if (epoch != speechEpoch) throw CancellationException("Speech interrupted")
                            started = true
                            _phase.value = PHASE_SPEAKING
                            say(sentence, epoch)
                        }
                    }
                } finally {
                    filler.cancel()
                }
                synchronized(speechState) {
                    if (epoch != speechEpoch) return@launch
                    _reply.value = r.text
                    _turnId.value = r.turnId
                    _lastTurn.value = Turn(r.turnId, text, r.text, r.tool)
                    _phase.value = PHASE_SPEAKING
                }
                JarvisLog.i("reply (${r.tool ?: "talk"}): ${r.text}")
                Notifications.showReply(this@JarvisCoreService, text, r.text)
                if (r.tool == "panic") shutUp()
                if (!r.spokenAlready) say(r.text, epoch)
                awaitSpeech()
                if (r.expectAnswer || (followUp && !r.endConversation && source != "typed")) next = "follow_up"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                JarvisLog.e("command failed", t)
            } finally {
                delay(TAIL_MS)   // let the speaker go quiet before the mic listens again
                synchronized(speechState) {
                    if (epoch == speechEpoch) {
                        listenRequest = next
                        if (next == null) _phase.value = PHASE_WAKE
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
        val score = audio?.let { runCatching { speaker.score(it) }.getOrNull() }
        JarvisLog.i("voice ID score ${score?.let { "%.2f".format(it) } ?: "none"} (need ${"%.2f".format(speakerThreshold)})")
        _lastVoiceScore.value = score
        return score != null && score >= speakerThreshold
    }

    private fun showPanel() {
        if (!showPanel) return
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
        val vocabulary = SttVocabularyLoader(this).load()
        val corrector = SttCorrector(vocabulary.contacts, vocabulary.apps)
        val stt = runCatching { SpeechToText(this, hotwords = vocabulary.hotwords) }
            .onSuccess { JarvisLog.i("speech-to-text: ${it.modelName}") }
            .onFailure { JarvisLog.e("speech-to-text not loaded (run tools/fetch_speech.sh)", it) }
            .getOrNull()
        var command: SpeechToText.Session? = null
        var interruption: SpeechToText.Session? = null
        var interruptionChunks = 0
        var commandSource = "voice"
        val commandAudio = ArrayList<ShortArray>()
        var wasBusy = false
        var wasSpeaking = false
        var enrollAudio: ArrayList<ShortArray>? = null
        val chunk = ShortArray(WakeWordDetector.CHUNK)
        val ring = AudioRing(WakeWordDetector.SAMPLE_RATE * RING_SECONDS)
        val minBuf = AudioRecord.getMinBufferSize(
            WakeWordDetector.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, WakeWordDetector.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, WakeWordDetector.SAMPLE_RATE * 2),   // 1 s: room for slow STT decode steps
        )
        val echoCanceler = runCatching {
            if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId)?.also {
                it.enabled = true
            } else null
        }.onFailure { JarvisLog.e("echo canceler unavailable", it) }.getOrNull()
        val echoCanceled = echoCanceler?.enabled == true
        JarvisLog.i("barge-in echo canceler: $echoCanceled")
        val tone = Chimes()
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
                        command = null
                        _micLevel.value = 0f
                        JarvisLog.i("capture ended ($commandSource): ${cmd.finishReason}")
                        cmd.close()
                        val audio = join(commandAudio)
                        val path = saveCommand(audio, cmd.raw)
                        val wakeId = if (commandSource == "voice") clips.lastWakeId else null
                        onCommand(corrector.correct(heard), cmd.raw, commandSource, wakeId, clips.lastWakePeak, path, audio, tone)
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
                    if (!wake && stt != null && BargeIn.canRecognizeStop(echoCanceled, TextToSpeech.playbackEnergy)) {
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
                        interruption?.close()
                        interruption = null
                        // Exclude loudspeaker pre-roll; capture fresh command audio after the interruption.
                        command = stt.session(stripWake = wake)
                        commandSource = "barge_in"
                        commandAudio.clear()
                        _heard.value = ""
                        _reply.value = ""
                        _phase.value = PHASE_HEARING
                        showPanel()
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
                val req = listenRequest
                if (req != null && stt != null) {
                    listenRequest = null
                    tone.listen()
                    command = stt.session(null, waitStart = if (req == "follow_up") FOLLOW_UP_WAIT else SpeechToText.WAIT_START)
                        .also { it.accept(chunk) }
                    commandSource = req
                    commandAudio.clear()
                    commandAudio.add(chunk.copyOf())
                    wasBusy = true
                    _heard.value = ""
                    _phase.value = if (req == "follow_up") PHASE_FOLLOW_UP else PHASE_HEARING
                    if (req == "tap") showPanel()
                    continue
                }
                if (wasBusy) { wasBusy = false; detector.reset() }   // drop audio from before the command
                val score = detector.process(chunk)
                _lastScore.value = score
                if (clips.onChunk(score, threshold, ring, tone) && stt != null) {
                    val preRoll = ring.last(PRE_ROLL_SAMPLES)
                    command = stt.session(preRoll)
                    commandSource = "voice"
                    commandAudio.clear()
                    commandAudio.add(preRoll)
                    wasBusy = true
                    _heard.value = ""
                    _reply.value = ""
                    _phase.value = PHASE_HEARING
                    showPanel()
                }
            }
        } finally {
            interruption?.close()
            command?.close()
            stt?.close()
            rec.stop()
            echoCanceler?.release()
            rec.release()
            tone.close()
            detector.close()
            _running.value = false
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
        tone: Chimes,
    ) {
        JarvisLog.i("heard ($source): \"$text\"")
        if (source == "voice") _wakeId.value = wakeId
        if (wakeId != null) Notifications.showWakeCheck(this, wakeId, wakeScore, heard = text.ifEmpty { "(nothing)" })
        if (text.isEmpty()) {
            _heard.value = if (source == "follow_up") "" else AssistantActivity.NOTHING_HEARD
            tone.timeout()
            _phase.value = PHASE_WAKE
            return
        }
        ask(text, raw, source, wakeId, audioPath, audio)
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
                tone.wake()
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
        private val COMMON_PHRASES = PhraseCache.COMMON.toSet()
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
        private const val FILLER_MS = 1_500L
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
        const val PHASE_HEARING = "Listening to you…"
        const val PHASE_SPEAKING = "Speaking…"
        const val PHASE_THINKING = "Thinking…"
        const val PHASE_FOLLOW_UP = "Anything else?"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
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

        fun start(context: Context) =
            context.startForegroundService(Intent(context, JarvisCoreService::class.java))

        /** Save the last 3 s as a "missed" clip (wake word was said but not detected). */
        fun missed(context: Context) = context.startService(
            Intent(context, JarvisCoreService::class.java).setAction(ACTION_MISSED)
        )

        private fun send(context: Context, action: String, text: String? = null, source: String? = null) =
            context.startForegroundService(
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

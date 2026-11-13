package com.alrasin.jarvis.service

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.backgroundWork
import com.alrasin.jarvis.voice.Engine
import com.alrasin.jarvis.voice.PhraseCache
import com.alrasin.jarvis.voice.SpeechGate
import com.alrasin.jarvis.voice.SpeechText
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.Voices
import com.alrasin.jarvis.voice.Wav
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The loaded voice and its phrase cache: switching voice / speed, making audio for a piece of text,
 * and warming common short replies in the background when [idle] says the assistant is quiet.
 */
class VoiceBox(
    private val context: Context,
    private val scope: CoroutineScope,
    private val idle: () -> Boolean,
) {
    @Volatile private var tts: Deferred<TextToSpeech?>? = null
    /** Generating and closing a voice must not overlap (native code). */
    private val ttsLock = Mutex()
    private var phrases: PhraseCache? = null
    private var phraseWarm: Job? = null
    @Volatile private var cachedAudio: Map<String, PhraseCache.Audio> = emptyMap()
    @Volatile private var voiceSpeed = 1.0f
    @Volatile private var onlineVoice = false
    /** Optional recorded wake reply pushed by hand to `files/sounds/wake_ack.wav`; used instead of the spoken "Uh-huh." */
    @Volatile private var wakeClip: PhraseCache.Audio? = null

    /** Google phone voice loaded: it is fast enough to speak whole phrases, which sounds more natural (D-054). */
    @Volatile var phoneEngine = false
        private set

    fun isCached(text: String): Boolean = cachedAudio.containsKey(SpeechText.normalize(text))

    /** Audio to play right after the wake word, or null to use a tone. */
    fun wakeAudio(): PhraseCache.Audio? = wakeClip ?: cachedAudio[SpeechText.normalize(WAKE_REPLY)]

    /** Audio for [text], or null when [wanted] turned false (the user said "stop") or no voice is loaded. */
    suspend fun make(text: String, wanted: () -> Boolean): PhraseCache.Audio? =
        (if (SpeechText.hasBangla(text)) null else cachedAudio[SpeechText.normalize(text)]) ?: ttsLock.withLock {
            if (wanted()) tts?.await()?.let { v -> audioFor(v, text) } else null
        }

    suspend fun setSpeed(speed: Float) = ttsLock.withLock {
        voiceSpeed = speed
        cachedAudio = emptyMap()
        readyVoice()?.let { v ->
            v.speed = speed
            warmPhrases(v)
        }
    }

    suspend fun setOnline(on: Boolean) = ttsLock.withLock {
        onlineVoice = on
        readyVoice()?.online = on
    }

    fun loadWakeClip() {
        wakeClip = runCatching {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, "sounds/wake_ack.wav")
            if (!file.isFile) return
            val pcm = Wav.read(file)
            if (pcm.isEmpty()) null else PhraseCache.Audio(FloatArray(pcm.size) { pcm[it] / 32768f }, Wav.sampleRate(file))
        }.onFailure { JarvisLog.e("wake clip failed", it) }.getOrNull()
    }

    /** Loads voice [id] (falls back to the built-in voice if it is not downloaded or fails), then frees the old one. */
    suspend fun switchVoice(id: String) {
        var old: Deferred<TextToSpeech?>? = null
        val next = ttsLock.withLock {
            currentCoroutineContext().ensureActive()
            old = tts
            phraseWarm?.cancel()
            phrases = null
            cachedAudio = emptyMap()
            // Teardown must be able to await and release a native voice even if loading is cancelled.
            scope.async(Dispatchers.IO + NonCancellable) {
                val v = Voices.byId(id).takeIf { Voices.installed(context, it.pack) } ?: Voices.byId(Voices.BUILT_IN)
                runCatching { TextToSpeech(context, v, speed = voiceSpeed) }
                    .recoverCatching { if (v.id == Voices.BUILT_IN) throw it else TextToSpeech(context, speed = voiceSpeed) }
                    .onSuccess { JarvisLog.i("voice: ${it.voice.id}"); phoneEngine = it.voice.pack.engine == Engine.ANDROID }
                    .onFailure { JarvisLog.e("voice not loaded (run tools/fetch_speech.sh)", it) }
                    .getOrNull()
            }.also { tts = it }
        }
        try {
            val loaded = next.await()
            ttsLock.withLock {
                if (tts === next) loaded?.let {
                    it.speed = voiceSpeed
                    it.online = onlineVoice
                    warmPhrases(it)
                }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                old?.await()?.let { o -> ttsLock.withLock { o.close() } }
            }
        }
    }

    /** A command started: warming must not compete with the reply for the CPU. */
    fun stopWarming() {
        phraseWarm?.cancel()
    }

    /** Stops warming and frees the voice (runs on its own scope: the owner is being destroyed). */
    fun close() {
        phraseWarm?.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            ttsLock.withLock {
                val voice = tts
                tts = null
                runCatching { voice?.await()?.close() }.onFailure { JarvisLog.e("voice close failed", it) }
            }
        }
    }

    /** Common short replies come from [PhraseCache] (made once per voice + speed), the rest is made now. */
    private fun audioFor(v: TextToSpeech, text: String): PhraseCache.Audio {
        // Bangla is cut out by normalize; the voice splits it off itself. Chat lines, never cached.
        if (SpeechText.hasBangla(text)) return SpeechGate.synth { v.generate(text) }.let { PhraseCache.Audio(it.samples, it.sampleRate) }
        val spoken = SpeechText.normalize(text)
        val common = spoken in COMMON_PHRASES
        var cache = if (common) phrases?.takeIf { it.voiceId == v.voice.id && it.speed == v.speed } else null
        cachedAudio[spoken]?.let { return it }
        cache?.get(spoken)?.let {
            cachedAudio = cachedAudio + (spoken to it)
            return it
        }
        val made = SpeechGate.synth { v.generate(spoken) }
        if (common && cache == null) {
            cache = PhraseCache(File(context.cacheDir, "phrases"), v.voice.id, v.speed, made.sampleRate)
            phrases = cache
        }
        cache?.put(spoken, made.samples, made.sampleRate)
        val audio = PhraseCache.Audio(made.samples, made.sampleRate)
        if (common) cachedAudio = cachedAudio + (spoken to audio)
        return audio
    }

    /** Pre-makes common replies on a disposable, single-thread engine while idle; replies never share its lock. */
    private fun warmPhrases(v: TextToSpeech) {
        phraseWarm?.cancel()
        val speed = v.speed
        val voice = v.voice
        val cache = PhraseCache(File(context.cacheDir, "phrases"), voice.id, speed, v.sampleRate)
        phrases = cache
        phraseWarm = scope.launch(Dispatchers.IO) {
            fun current() = readyVoice() === v && v.speed == speed
            fun quiet() = idle() && current()
            for (text in PhraseCache.COMMON) {
                ensureActive()
                val key = SpeechText.normalize(text)
                cache.get(key)?.let { audio ->
                    ttsLock.withLock { if (current()) cachedAudio = cachedAudio + (key to audio) }
                }
            }
            // Feedback first (needed on every command), then the other common replies, so they never wait for synth (§K 15.2).
            val missing = (FEEDBACK_PHRASES + PhraseCache.COMMON).distinct()
                .filter { cache.get(SpeechText.normalize(it)) == null }
            if (missing.isEmpty()) return@launch
            while (isActive && !quiet()) delay(200)
            ensureActive()
            backgroundWork {
                TextToSpeech(context, voice, threads = 1, speed = speed).use { warmer ->
                    for (text in missing) {
                        if (!isActive || !quiet()) break
                        val made = warmer.generate(text)
                        if (!isActive || !quiet()) break
                        cache.put(SpeechText.normalize(text), made.samples, made.sampleRate)
                    }
                }
            }
            for (text in missing) {
                ensureActive()
                cache.get(SpeechText.normalize(text))?.let { audio ->
                    ttsLock.withLock {
                        if (current()) cachedAudio = cachedAudio + (SpeechText.normalize(text) to audio)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun readyVoice(): TextToSpeech? = tts?.takeIf { it.isCompleted }?.getCompleted()

    companion object {
        /** Short "thinking" phrases spoken while a slow answer is being made. */
        val FILLERS = listOf("Let me check.", "On it.", "Hmm, one sec.")
        private const val WAKE_REPLY = "Uh-huh."
        private val FEEDBACK_PHRASES = FILLERS + WAKE_REPLY
        private val COMMON_PHRASES = PhraseCache.COMMON.map(SpeechText::normalize).toSet()
    }
}

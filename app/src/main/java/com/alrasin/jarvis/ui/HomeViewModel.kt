package com.alrasin.jarvis.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.WakeEventDao
import com.alrasin.jarvis.service.CoreState
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.service.WakeFeedback
import com.alrasin.jarvis.voice.BanglaSpeechToText
import com.alrasin.jarvis.voice.NoiseCleaner
import com.alrasin.jarvis.voice.SpeechScore
import com.alrasin.jarvis.voice.SpeechToText
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.Voices
import com.alrasin.jarvis.voice.Wav
import com.alrasin.jarvis.voice.WakePhrase
import com.alrasin.jarvis.voice.WakeModels
import com.alrasin.jarvis.voice.WakeWordDetector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val app: Application,
    private val settings: Settings,
    private val dao: WakeEventDao,
) : AndroidViewModel(app) {
    private val requestedRunning = MutableStateFlow<Boolean?>(null)
    val running = combine(CoreState.running, requestedRunning) { actual, requested -> requested ?: actual }
        .stateIn(viewModelScope, SharingStarted.Eagerly, CoreState.running.value)
    private var listeningChange: Job? = null
    private val listeningLock = Mutex()
    val lastScore = CoreState.lastScore
    val modelName = CoreState.modelName
    val phase = CoreState.phase
    val heard = CoreState.heard
    val events = dao.recent().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val counts = dao.counts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val threshold = settings.wakeThreshold.stateIn(viewModelScope, SharingStarted.Eagerly, Settings.DEFAULT_THRESHOLD)
    val log = JarvisLog.lines
    private var player: MediaPlayer? = null

    fun start() = setListening(true)

    fun stop() = setListening(false)

    private fun setListening(enabled: Boolean) {
        requestedRunning.value = enabled
        listeningChange?.cancel()
        listeningChange = viewModelScope.launch(Dispatchers.IO) {
            try {
                listeningLock.withLock {
                    settings.setListeningEnabled(enabled)
                    val dispatched = if (enabled) JarvisCoreService.start(app) else JarvisCoreService.stop(app)
                    if (dispatched == null) return@launch
                }
                withTimeoutOrNull(45_000) { CoreState.running.first { it == enabled } }
            } catch (failure: RuntimeException) {
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                JarvisLog.e("listening change failed", failure)
            } finally {
                if (kotlinx.coroutines.currentCoroutineContext().isActive) requestedRunning.value = null
            }
        }
    }

    fun missed() = viewModelScope.launch(Dispatchers.IO) {
        JarvisCoreService.missed(app)
        JarvisLog.i("missed: saving last 3 s")
    }

    fun setThreshold(v: Float) = viewModelScope.launch { settings.setWakeThreshold(v) }

    fun setVerdict(id: Long, verdict: String) = viewModelScope.launch(Dispatchers.IO) {
        WakeFeedback.setVerdict(app, dao, id, verdict)
    }

    fun play(path: String) {
        player?.release()
        player = runCatching {
            MediaPlayer().apply {
                setOnPreparedListener { it.start() }
                setDataSource(path)
                prepareAsync()
            }
        }.onFailure { JarvisLog.e("play failed", it) }.getOrNull()
    }

    /**
     * Debug: score every WAV (16 kHz mono) in [dir] and log the max score per file.
     * Used to check the Kotlin port against Python:
     * `adb shell am start -n com.alrasin.jarvis/.ui.MainActivity --es test_dir <dir>`
     */
    fun scoreDir(dir: String) = viewModelScope.launch(Dispatchers.Default) {
        runCatching {
            val m = WakeModels.load(app)
            WakeWordDetector(m.mel, m.embedding, m.wake).use { det ->
                val files = File(dir).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
                for (f in files) {
                    det.reset()
                    val audio = Wav.read(f)
                    val t0 = System.nanoTime()
                    var max = 0f
                    var maxAt = 0
                    var i = 0
                    while (i + WakeWordDetector.CHUNK <= audio.size) {
                        val s = det.process(audio.copyOfRange(i, i + WakeWordDetector.CHUNK))
                        if (s > max) { max = s; maxAt = i }
                        i += WakeWordDetector.CHUNK
                    }
                    val cpu = (System.nanoTime() - t0) / 1e9f / (audio.size / 16000f)
                    JarvisLog.i(
                        "TEST ${m.wakeName} ${f.name} max=${"%.3f".format(max)} at=${"%.2f".format(maxAt / 16000f)}s " +
                            "cpu=${"%.0f".format(cpu * 100)}% of realtime",
                    )
                }
                JarvisLog.i("TEST done (${files.size} files)")
            }
        }.onFailure { JarvisLog.e("TEST failed", it) }
    }

    /**
     * Debug: speech-to-text on every 16 kHz WAV in [dir], then say the first result. Logs speed, and word error rate
     * for clips with a `<name>.txt` of what was said; appends one summary row to `files/bench/stt.md` (#48).
     * `adb shell am start -n com.alrasin.jarvis/.ui.MainActivity --es stt_dir <dir> [--es stt_model <dir>|bangla]`
     * Add `--ez stt_denoise true` to clean each clip with the noise model first (§K 3.5); rtf then includes cleaning.
     */
    fun speechTest(
        dir: String, model: String? = null, speak: Boolean = true, denoise: Boolean = false,
    ) = viewModelScope.launch(Dispatchers.Default) {
        val cleaner = NoiseCleaner(app)
        runCatching {
            if (denoise) check(cleaner.ready()) { "noise cleaning model missing" }
            fun cleaned(audio: ShortArray): ShortArray {
                if (!denoise) return audio
                cleaner.reset()
                val out = ShortArray(audio.size)
                var i = 0
                while (i < audio.size) {
                    val n = minOf(WakeWordDetector.CHUNK, audio.size - i)
                    val part = cleaner.clean(audio.copyOfRange(i, i + n), enabled = true)
                    part.copyInto(out, i, 0, minOf(part.size, out.size - i))
                    i += n
                }
                return out
            }
            val files = File(dir).listFiles { f -> f.name.endsWith(".wav") }.orEmpty().sortedBy { it.name }
            val clips = mutableListOf<SpeechScore.Clip>()
            val texts = mutableListOf<String>()
            fun run(name: String, loadMs: Long, decode: (ShortArray) -> String) {
                JarvisLog.i("STT $name loaded in $loadMs ms")
                for (f in files) {
                    val raw = Wav.read(f)
                    val t0 = System.nanoTime()
                    val audio = cleaned(raw)
                    val text = decode(audio)
                    val seconds = audio.size / 16000f
                    val rtf = (System.nanoTime() - t0) / 1e9f / seconds
                    val expected = File(f.path.removeSuffix(".wav") + ".txt").takeIf { it.isFile }?.readText()
                    val wer = expected?.let { SpeechScore.wer(it, text) }
                    clips += SpeechScore.Clip(f.name, seconds, rtf, wer)
                    texts += text
                    JarvisLog.i("STT ${f.name} rtf=${"%.2f".format(rtf)}${wer?.let { " wer=${"%.2f".format(it)}" }.orEmpty()} \"$text\" → \"${WakePhrase.strip(text)}\"")
                }
                val row = SpeechScore.summaryRow(if (denoise) "$name +denoise" else name, loadMs, clips)
                val out = File(app.getExternalFilesDir(null), "bench/stt.md").apply { parentFile?.mkdirs() }
                if (!out.exists()) out.writeText(SpeechScore.HEADER + "\n")
                out.appendText(row + "\n")
                JarvisLog.i("STT summary $row")
            }
            var t0 = System.nanoTime()
            if (model == "bangla") BanglaSpeechToText(app).use { stt ->
                stt.loadForBench()
                run("Whisper small bn→en", (System.nanoTime() - t0) / 1_000_000, stt::decodeForBench)
            } else SpeechToText(app, model?.let(::File)).use { stt ->
                run(stt.modelName, (System.nanoTime() - t0) / 1_000_000) { audio ->
                    stt.session().use { s ->
                        var i = 0
                        while (i < audio.size) {
                            val n = minOf(WakeWordDetector.CHUNK, audio.size - i)
                            s.accept(audio.copyOfRange(i, i + n))
                            i += n
                        }
                        s.accept(ShortArray(16_000))   // 1 s silence so the last words are decoded
                        s.text
                    }
                }
            }
            if (!speak) { JarvisLog.i("STT test done"); return@runCatching }
            t0 = System.nanoTime()
            TextToSpeech(app).use { tts ->
                JarvisLog.i("TTS loaded in ${(System.nanoTime() - t0) / 1_000_000} ms")
                t0 = System.nanoTime()
                tts.speak("You said: ${texts.firstOrNull() ?: "nothing"}")
                JarvisLog.i("TTS generate+play ${(System.nanoTime() - t0) / 1_000_000} ms")
            }
            JarvisLog.i("STT test done")
        }.onFailure { JarvisLog.e("STT test failed", it) }
        cleaner.close()
    }

    /**
     * Debug: time each installed voice ([ids] = comma list of voice ids, or "all"), 2 and 4 threads.
     * `adb shell am start -n com.alrasin.jarvis/.ui.MainActivity --es voice_bench kokoro-heart,kitten-m2`
     */
    fun voiceBench(ids: String) = viewModelScope.launch(Dispatchers.Default) {
        val text = "Sure. I've set an alarm for seven tomorrow morning. By the way, it looks like rain later, so you might want an umbrella."
        val out = java.io.File(app.getExternalFilesDir(null), "voice_bench").apply { mkdirs() }
        val voices = Voices.ALL.filter { (ids == "all" || it.id in ids.split(',')) && Voices.installed(app, it.pack) } +
            ids.split(',').filter { id -> id.startsWith(Voices.ANDROID_PREFIX) && Voices.ALL.none { it.id == id } }.map { Voices.android(it.removePrefix(Voices.ANDROID_PREFIX)) }
        for (voice in voices) {
            for (threads in listOf(2, 4)) runCatching {
                var t0 = System.nanoTime()
                TextToSpeech(app, voice, threads).use { tts ->
                    val load = (System.nanoTime() - t0) / 1_000_000
                    t0 = System.nanoTime()
                    val a = tts.generate(text)
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    val audioMs = a.samples.size * 1000L / a.sampleRate
                    if (threads == 2) com.alrasin.jarvis.voice.Wav.write(
                        java.io.File(out, voice.id.replace(':', '_') + ".wav"),
                        ShortArray(a.samples.size) { (a.samples[it].coerceIn(-1f, 1f) * 32767).toInt().toShort() }, a.sampleRate,
                    )
                    JarvisLog.i("VOICE ${voice.id} t=$threads load=${load}ms gen=${ms}ms audio=${audioMs}ms rtf=${"%.2f".format(ms.toFloat() / audioMs)}")
                }
            }.onFailure { JarvisLog.e("VOICE ${voice.id} failed", it) }
        }
        JarvisLog.i("VOICE done")
    }

    override fun onCleared() {
        player?.release()
    }
}

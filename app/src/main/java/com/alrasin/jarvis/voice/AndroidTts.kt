package com.alrasin.jarvis.voice

import android.content.Context
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import com.k2fsa.sherpa.onnx.GeneratedAudio
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.speech.tts.TextToSpeech as SystemTts

/**
 * The phone's own speech engine (Google speech services on this phone, D-053). Audio is made to a temp WAV
 * and read back, so it plays through the same path as the sherpa voices (cache, stop, barge-in).
 * Must not be created on the main thread: it waits for the engine to connect.
 */
class AndroidTts(context: Context, voiceName: String?) : AutoCloseable {
    private val tts: SystemTts
    private val dir = File(context.cacheDir, "android_tts").apply { mkdirs() }
    private val ids = AtomicInteger()
    private val waits = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    init {
        val ready = CountDownLatch(1)
        var status = SystemTts.ERROR
        tts = SystemTts(context.applicationContext, { status = it; ready.countDown() }, GOOGLE)
        check(ready.await(INIT_S, TimeUnit.SECONDS) && status == SystemTts.SUCCESS) { "phone speech engine not ready" }
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { waits.remove(id)?.countDown() }
            @Deprecated("old API") override fun onError(id: String) { failed += id; waits.remove(id)?.countDown() }
            override fun onError(id: String, code: Int) { failed += id; waits.remove(id)?.countDown() }
        })
        val voice = voiceName?.let { n -> tts.voices.orEmpty().firstOrNull { it.name == n } }
        if (voice != null) tts.voice = voice else tts.language = Locale.US
    }

    /** Offline English voices the engine has installed, best first. */
    fun voices(): List<android.speech.tts.Voice> = tts.voices.orEmpty()
        .filter { it.locale.language == "en" && !it.isNetworkConnectionRequired && !it.name.endsWith("-language") &&
            SystemTts.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
        .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })

    fun generate(text: String, speed: Float): GeneratedAudio {
        val id = "j${ids.incrementAndGet()}"
        val file = File(dir, "$id.wav")
        val done = CountDownLatch(1)
        waits[id] = done
        try {
            tts.setSpeechRate(speed)
            check(tts.synthesizeToFile(text, Bundle(), file, id) == SystemTts.SUCCESS) { "phone speech failed to start" }
            check(done.await(SYNTH_S, TimeUnit.SECONDS)) { "phone speech timed out" }
            check(failed.remove(id).not()) { "phone speech failed" }
            val pcm = Wav.read(file)
            return GeneratedAudio(FloatArray(pcm.size) { pcm[it] / 32768f }, Wav.sampleRate(file))
        } finally {
            waits.remove(id)
            file.delete()
        }
    }

    override fun close() = tts.shutdown()

    companion object {
        const val GOOGLE = "com.google.android.tts"
        private const val INIT_S = 20L   // cold start of Google TTS took ~8 s on the phone
        private const val SYNTH_S = 15L
    }
}

package com.alrasin.jarvis.voice

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice as SystemVoice
import com.alrasin.jarvis.core.JarvisLog
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
 * When [online] is on and the internet works, the same voice's online version ("-network") is used: it sounds
 * more human. If it is slow or fails, that piece is made offline and online is skipped for a while (D-074).
 */
class AndroidTts(context: Context, private val voiceName: String?) : AutoCloseable {
    private val app = context.applicationContext
    private val dir = File(context.cacheDir, "android_tts").apply { mkdirs() }
    private val ids = AtomicInteger()
    private val waits = java.util.concurrent.ConcurrentHashMap<String, CountDownLatch>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var localVoice: SystemVoice? = null
    @Volatile private var netVoice: SystemVoice? = null
    @Volatile private var netBlockedUntil = 0L
    /** Other-language voices by language code; null = none installed (looked up once). */
    private val langVoices = HashMap<String, SystemVoice?>()
    /** Use the online version of the voice when the internet works (setting, off by default). */
    @Volatile var online = false
    @Volatile private var tts: SystemTts = connect()

    private fun connect(): SystemTts {
        val ready = CountDownLatch(1)
        var status = SystemTts.ERROR
        val t = SystemTts(app, { status = it; ready.countDown() }, GOOGLE)
        if (!ready.await(INIT_S, TimeUnit.SECONDS) || status != SystemTts.SUCCESS) {
            t.shutdown()
            error("phone speech engine not ready")
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { waits.remove(id)?.countDown() }
            @Deprecated("old API") override fun onError(id: String) { failed += id; waits.remove(id)?.countDown() }
            override fun onError(id: String, code: Int) { failed += id; waits.remove(id)?.countDown() }
        })
        val voices = t.voices.orEmpty()
        val voice = voiceName?.let { n -> voices.firstOrNull { it.name == n } }
        localVoice = voice
        netVoice = voiceName?.let { n -> voices.firstOrNull { it.name == networkName(n) } }
        JarvisLog.i("phone voice $voiceName: online version ${if (netVoice != null) "found" else "missing"}")
        if (voice != null) t.voice = voice else t.language = Locale.US
        return t
    }

    /**
     * MIUI sometimes kills the Google speech process; the old connection then fails every call
     * (seen 2026-10-23: "phone speech failed to start" until restart). Reconnect once and retry.
     */
    @Synchronized
    fun generate(text: String, speed: Float): GeneratedAudio = try {
        synthOnline(text, speed) ?: synth(text, speed)
    } catch (e: IllegalStateException) {
        JarvisLog.e("phone speech failed, reconnecting", e)
        runCatching { tts.shutdown() }
        tts = connect()
        synth(text, speed)
    }

    /**
     * [text] said by an offline voice of [language] ("bn" = Bangla), then the normal voice is set back.
     * Null when the phone has no such voice installed (Google: Settings > Speech > install voice data).
     */
    @Synchronized
    fun generateIn(language: String, text: String, speed: Float): GeneratedAudio? {
        val voice = (if (language in langVoices) langVoices[language] else findVoice(language).also { langVoices[language] = it })
            ?: return null
        return try {
            tts.voice = voice
            synth(text, speed)
        } catch (e: IllegalStateException) {
            JarvisLog.e("phone $language speech failed, reconnecting", e)
            runCatching { tts.shutdown() }
            tts = connect()
            null
        } finally {
            localVoice?.let { tts.voice = it } ?: run { tts.language = Locale.US }
        }
    }

    /** Best installed offline voice for [language]; Bangladesh first. */
    private fun findVoice(language: String): SystemVoice? {
        val voice = tts.voices.orEmpty()
            .filter { it.locale.language == language && !it.isNetworkConnectionRequired &&
                SystemTts.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
            .minByOrNull { if (it.locale.country == "BD") 0 else 1 }
        JarvisLog.i("phone $language voice: ${voice?.name ?: "none installed"}")
        return voice
    }

    /** Online voice when allowed and working, else null (caller makes it offline). */
    private fun synthOnline(text: String, speed: Float): GeneratedAudio? {
        val net = netVoice ?: return null
        val local = localVoice ?: return null
        if (!online || System.currentTimeMillis() < netBlockedUntil || !internet()) return null
        tts.voice = net
        return try {
            synth(text, speed, NET_S)
        } catch (e: IllegalStateException) {
            JarvisLog.i("online voice failed, offline for ${NET_BLOCK_MS / 1000} s: ${e.message}")
            netBlockedUntil = System.currentTimeMillis() + NET_BLOCK_MS
            null
        } finally {
            tts.voice = local
        }
    }

    private fun internet(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun synth(text: String, speed: Float, waitS: Long = SYNTH_S): GeneratedAudio {
        val id = "j${ids.incrementAndGet()}"
        val file = File(dir, "$id.wav")
        val done = CountDownLatch(1)
        waits[id] = done
        try {
            tts.setSpeechRate(speed)
            check(tts.synthesizeToFile(text, Bundle(), file, id) == SystemTts.SUCCESS) { "phone speech failed to start" }
            check(done.await(waitS, TimeUnit.SECONDS)) { "phone speech timed out" }
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
        private const val NET_S = 4L                 // slower than this = worse than the offline voice
        private const val NET_BLOCK_MS = 2 * 60_000L

        /** "en-us-x-tpf-local" -> "en-us-x-tpf-network" (Google's online version of the same speaker). */
        fun networkName(local: String) = local.removeSuffix("-local") + "-network"
    }
}

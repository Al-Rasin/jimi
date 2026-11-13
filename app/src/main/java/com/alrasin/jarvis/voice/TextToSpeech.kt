package com.alrasin.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.alrasin.jarvis.core.JarvisLog
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Offline voice (PLAN 3.3, D-025, D-038) via sherpa-onnx, or the phone's own engine (D-053). [voice] picks the
 * engine (Piper / Supertonic / phone) and speaker;
 * the built-in Piper "Alan" is in APK assets `tts/`, other voices are downloaded to `files/voices/` (see [Voices]).
 * espeak-ng-data must be a real folder, so it is copied from assets to app files once (all engines share it).
 */
class TextToSpeech(
    context: Context,
    val voice: Voice = Voices.byId(Voices.BUILT_IN),
    threads: Int = 2,
    speed: Float = 1.0f,
) : AutoCloseable {
    private val tts: OfflineTts?
    private val phone: AndroidTts?
    private val app = context.applicationContext
    /** Sherpa voices cannot say Bangla: the phone engine is opened the first time Bangla comes (null = not there). */
    private var banglaEngine: AndroidTts? = null
    private var banglaTried = false
    @Volatile private var stopped = false
    /** 1.0 = normal; can change between sentences without reloading. */
    @Volatile var speed = speed
    /** Phone engine only: use Google's online version of the voice when the internet works (D-074). */
    var online: Boolean
        get() = phone?.online ?: false
        set(v) { phone?.online = v }

    init {
        phone = if (voice.pack.engine == Engine.ANDROID) AndroidTts(context, voice.id.removePrefix(Voices.ANDROID_PREFIX)) else null
        tts = if (phone != null) null else sherpa(context, threads)
        generate("Hi.")   // first run is ~4x slower; pay it now, not on the first answer
    }

    private fun sherpa(context: Context, threads: Int): OfflineTts {
        val pack = voice.pack
        val data = if (pack.engine == Engine.PIPER) copyEspeakData(context).path else ""
        val downloaded = PortableModels.dir(context, DIR)
        val localAlan = pack.id == Voices.BUILT_IN && ModelFiles.voiceReady(downloaded)
        val builtIn = pack.id == Voices.BUILT_IN && !localAlan
        fun f(name: String) = if (builtIn) "$DIR/$name"
            else File(if (localAlan) downloaded else Voices.dir(context, pack), name).path
        val model = when (pack.engine) {
            Engine.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = f(pack.model), tokens = f("tokens.txt"), dataDir = data,
                    noiseScale = 0.667f, noiseScaleW = 0.8f, lengthScale = 1.0f),
                numThreads = threads,
            )
            Engine.ANDROID -> error("not a sherpa voice")
            Engine.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = f("duration_predictor.int8.onnx"), textEncoder = f("text_encoder.int8.onnx"),
                    vectorEstimator = f("vector_estimator.int8.onnx"), vocoder = f("vocoder.int8.onnx"),
                    ttsJson = f("tts.json"), unicodeIndexer = f("unicode_indexer.bin"), voiceStyle = f("voice.bin"),
                ),
                numThreads = threads,
            )
        }
        return OfflineTts(if (builtIn) context.assets else null, OfflineTtsConfig(model = model))
    }

    /** The phone engine's rate is known only after the first sentence (Google: 24 kHz). */
    @Volatile var sampleRate: Int = tts?.sampleRate() ?: 24_000
        private set

    fun generate(text: String): GeneratedAudio {
        if (SpeechText.hasBangla(text)) return mixed(text)
        val spoken = SpeechText.normalize(text)
        if (spoken.isBlank()) return GeneratedAudio(FloatArray(0), 24_000)
        val start = System.currentTimeMillis()
        val audio = if (phone != null) {
            phone.generate(spoken, speed).also { if (it.samples.isNotEmpty()) sampleRate = it.sampleRate }
        } else if (voice.pack.engine == Engine.SUPERTONIC) {
            tts!!.generateWithConfig(spoken, GenerationConfig(sid = voice.sid, speed = speed, numSteps = SUPERTONIC_STEPS, extra = mapOf("lang" to "en")))
        } else {
            tts!!.generate(spoken, sid = voice.sid, speed = speed)
        }
        val made = System.currentTimeMillis() - start
        JarvisLog.i("tts ${voice.id}: ${audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)} ms audio in $made ms")
        return audio
    }

    /** English parts in this voice, Bangla parts in the phone's Bangla voice; no Bangla voice = say so once. */
    private fun mixed(text: String): GeneratedAudio {
        val engine = phone ?: bangla()
        var told = false
        val pieces = SpeechText.parts(text).mapNotNull { part ->
            val audio = if (!part.bangla) generate(part.text)
                else engine?.generateIn("bn", part.text, speed)
                    ?: if (told) null else generate(NO_BANGLA).also { told = true }
            audio?.takeIf { it.samples.isNotEmpty() }
        }
        return join(pieces)
    }

    @Synchronized
    private fun bangla(): AndroidTts? {
        if (!banglaTried) {
            banglaTried = true
            banglaEngine = runCatching { AndroidTts(app, null) }.onFailure { JarvisLog.e("no phone engine for Bangla", it) }.getOrNull()
        }
        return banglaEngine
    }

    /** Stops the sentence being played now (user tapped stop / said "stop"). */
    fun stop() {
        stopped = true
        stopPlayback()
    }

    /** Blocks until the sentence has been played or [stop] is called. Returns false if stopped. */
    fun speak(text: String): Boolean {
        stopped = false
        val chunks = if (SpeechText.hasBangla(text)) listOf(text)
            else if (voice.pack.engine == Engine.SUPERTONIC) SpeechText.chunks(text)
            else listOf(SpeechText.normalize(text)).filter { it.isNotBlank() }
        for (chunk in chunks) {
            if (stopped) return false
            val audio = generate(chunk)
            if (stopped || !playPcm(audio.samples, audio.sampleRate, SpeechText.pauseAfterMs(chunk)) { stopped }) return false
        }
        return !stopped
    }

    /** Plays audio made by [generate] (the service makes the next sentence while this one plays). */
    fun play(audio: GeneratedAudio): Boolean {
        stopped = false
        return playPcm(audio.samples, audio.sampleRate) { stopped }
    }

    override fun close() {
        tts?.release()
        phone?.close()
        banglaEngine?.close()
    }

    companion object {
        private const val DIR = "tts"
        private const val NO_BANGLA = "a message in Bangla"
        private const val PART_GAP_MS = 120

        /** Pieces back to back at the first piece's rate, a short gap between (voices may differ: 22 vs 24 kHz). */
        internal fun join(pieces: List<GeneratedAudio>): GeneratedAudio {
            if (pieces.isEmpty()) return GeneratedAudio(FloatArray(0), 24_000)
            val rate = pieces.first().sampleRate
            val gap = FloatArray(rate * PART_GAP_MS / 1000)
            val parts = pieces.flatMapIndexed { i, p ->
                val pcm = resample(p.samples, p.sampleRate, rate)
                if (i == 0) listOf(pcm) else listOf(gap, pcm)
            }
            val out = FloatArray(parts.sumOf { it.size })
            var at = 0
            for (p in parts) { p.copyInto(out, at); at += p.size }
            return GeneratedAudio(out, rate)
        }

        /** Straight-line resample; good enough for speech between two close rates. */
        internal fun resample(pcm: FloatArray, from: Int, to: Int): FloatArray {
            if (from == to || pcm.isEmpty()) return pcm
            val n = (pcm.size.toLong() * to / from).toInt().coerceAtLeast(1)
            return FloatArray(n) { i ->
                val x = i.toDouble() * from / to
                val a = x.toInt().coerceAtMost(pcm.size - 1)
                val b = (a + 1).coerceAtMost(pcm.size - 1)
                val f = (x - a).toFloat()
                pcm[a] * (1 - f) + pcm[b] * f
            }
        }
        /** Supertonic quality/speed: more denoising steps = cleaner voice, slower. */
        private const val SUPERTONIC_STEPS = 3
        private const val ESPEAK = "espeak-ng-data"

        /**
         * One long-lived audio stream for all speech. A new track per piece made a gap and a click at
         * every piece (track start-up is 100-300 ms on MIUI). Pieces are written back to back instead,
         * so the voice flows like one recording.
         */
        private val playbackLock = Any()
        private val writeLock = Any()
        private var playbackEpoch = 0L
        private var stream: Stream? = null
        /** Return this early from [playPcm] so the next piece is written before this one ends. */
        private const val LEAD_MS = 150
        private const val BUFFER_MS = 500
        /** Free the speaker this long after the last piece. */
        private const val IDLE_MS = 2_000L
        private const val STOP_RELEASE_MS = 300L
        private val releaser = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "tts-release").apply { isDaemon = true }
        }

        private class Segment(val start: Long, val pcm: FloatArray)
        private class Stream(val track: AudioTrack, val rate: Int) {
            /** Frames given to the track so far. */
            var written = 0L
            val segments = ArrayDeque<Segment>()
            @Volatile var lastUse = System.currentTimeMillis()
            /** Frames played; the raw value is an unsigned 32-bit counter. */
            fun head(): Long = track.playbackHeadPosition.toLong() and 0xffffffffL
        }

        /** Speech loudness (1 = normal). Bedtime mode lowers it so Jimi whispers at night. */
        @Volatile var gain: Float = 1f

        val isPlaying: Boolean
            get() {
                synchronized(playbackLock) {
                    val s = stream ?: return false
                    return runCatching { s.head() < s.written }.getOrDefault(false)
                }
            }

        /** RMS near the playback head; queued speech must not suppress a quiet microphone window. */
        val playbackEnergy: Float
            get() {
                synchronized(playbackLock) {
                    val s = stream ?: return 0f
                    val head = runCatching { s.head() }.getOrElse { return 0f }
                    while (s.segments.isNotEmpty() && s.segments.first().let { it.start + it.pcm.size } <= head) {
                        s.segments.removeFirst()
                    }
                    val seg = s.segments.firstOrNull()?.takeIf { head >= it.start } ?: return 0f
                    val from = (head - seg.start).toInt()
                    val to = (from + s.rate / 50).coerceAtMost(seg.pcm.size)
                    var energy = 0.0
                    for (i in from until to) energy += seg.pcm[i] * seg.pcm[i]
                    return sqrt(energy / (to - from).coerceAtLeast(1)).toFloat()
                }
            }

        /** Does not wait for native synthesis or the service's TTS lock. */
        fun stopPlayback() {
            synchronized(playbackLock) {
                playbackEpoch++
                val s = stream ?: return
                stream = null
                runCatching { s.track.pause(); s.track.flush(); s.track.stop() }
                // a writer may still hold the track for a moment; release after it sees the new epoch
                releaser.schedule({ synchronized(playbackLock) { runCatching { s.track.release() } } },
                    STOP_RELEASE_MS, TimeUnit.MILLISECONDS)
            }
        }

        /**
         * Plays mono float PCM on the assistant audio stream, then [pauseMs] of silence; blocks until it
         * is nearly done or [stop] returns true. Waits on the real playback position (not the clock): on
         * MIUI a track can start 200+ ms late. [onStarted] runs when this piece is first heard.
         */
        fun playPcm(
            pcm: FloatArray,
            rate: Int,
            pauseMs: Int = 0,
            onStarted: () -> Unit = {},
            stop: () -> Boolean = { false },
        ): Boolean {
            val epoch = synchronized(playbackLock) { playbackEpoch }
            if (stop()) return false
            val g = gain
            val samples = PcmShape.shape(pcm, rate, pauseMs).let { s -> if (g >= 1f) s else FloatArray(s.size) { s[it] * g } }
            if (samples.isEmpty()) return true
            synchronized(writeLock) {
                val s = streamFor(rate, epoch, stop) ?: return cancel(epoch, stop)
                fun live() = epoch == playbackEpoch && stream === s
                val start = synchronized(playbackLock) {
                    if (!live()) return cancel(epoch, stop)
                    val played = runCatching { s.head() }.getOrDefault(0L)
                    while (s.segments.firstOrNull()?.let { it.start + it.pcm.size <= played } == true) s.segments.removeFirst()
                    s.written.also {
                        s.written += samples.size
                        s.segments.addLast(Segment(it, samples))
                    }
                }
                val end = start + samples.size
                var started = false
                fun heard(head: Long) {
                    if (!started && head > start) { started = true; onStarted() }
                }
                var offset = 0
                while (offset < samples.size) {
                    val (wrote, head) = synchronized(playbackLock) {
                        if (!live()) return cancel(epoch, stop)
                        s.lastUse = System.currentTimeMillis()
                        val n = s.track.write(samples, offset, samples.size - offset, AudioTrack.WRITE_NON_BLOCKING)
                        if (n > 0 && s.track.playState != AudioTrack.PLAYSTATE_PLAYING) s.track.play()
                        n to s.head()
                    }
                    if (wrote < 0 || stop()) return cancel(epoch, stop, error = wrote < 0)
                    offset += wrote
                    heard(head)
                    if (wrote == 0) Thread.sleep(10)
                }
                // done when only LEAD_MS is left, so the next piece follows with no gap
                val done = maxOf(end - rate * LEAD_MS / 1000, start + 1)
                var deadline = 0L
                while (true) {
                    val head = synchronized(playbackLock) {
                        if (!live()) return cancel(epoch, stop)
                        s.lastUse = System.currentTimeMillis()
                        s.head()
                    }
                    if (stop()) return cancel(epoch, stop)
                    heard(head)
                    if (head >= done) break
                    if (deadline == 0L) deadline = System.currentTimeMillis() + (end - head) * 1000 / rate + 2000
                    if (System.currentTimeMillis() > deadline) break   // playback stuck; do not hang the reply
                    Thread.sleep(10)
                }
                if (!started) onStarted()
                releaseWhenIdle()
                return true
            }
        }

        /** The open stream, or a new one; a stream at another sample rate is played out first. */
        private fun streamFor(rate: Int, epoch: Long, stop: () -> Boolean): Stream? {
            var deadline = 0L
            while (true) {
                synchronized(playbackLock) {
                    if (epoch != playbackEpoch) return null
                    val s = stream ?: return openStream(rate).also { stream = it }
                    if (s.rate == rate) return s.also { it.lastUse = System.currentTimeMillis() }
                    val head = runCatching { s.head() }.getOrDefault(s.written)
                    if (deadline == 0L) deadline = System.currentTimeMillis() + (s.written - head) * 1000 / s.rate + 2000
                    if (head >= s.written || System.currentTimeMillis() > deadline) {
                        stream = null
                        runCatching { s.track.stop() }
                        runCatching { s.track.release() }
                    }
                }
                if (stop()) return null
                Thread.sleep(10)
            }
        }

        private fun openStream(rate: Int): Stream {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(min, rate * 4 * BUFFER_MS / 1000))
                .build()
            return Stream(track, rate)
        }

        /** Our own [stop] (not [stopPlayback]) ended the piece: silence the stream too, as before. */
        private fun cancel(epoch: Long, stop: () -> Boolean, error: Boolean = false): Boolean {
            if (error) JarvisLog.e("tts: audio write failed")
            if (synchronized(playbackLock) { epoch == playbackEpoch } && (error || stop())) stopPlayback()
            return false
        }

        private fun releaseWhenIdle() {
            releaser.schedule({
                synchronized(playbackLock) {
                    val s = stream ?: return@synchronized
                    val idle = System.currentTimeMillis() - s.lastUse >= IDLE_MS &&
                        runCatching { s.head() >= s.written }.getOrDefault(true)
                    if (idle) {
                        stream = null
                        runCatching { s.track.stop() }
                        runCatching { s.track.release() }
                    }
                }
            }, IDLE_MS + 100, TimeUnit.MILLISECONDS)
        }

        private fun copyEspeakData(context: Context): File {
            val downloaded = File(PortableModels.dir(context, DIR), ESPEAK)
            if (ModelFiles.voiceReady(PortableModels.dir(context, DIR))) return downloaded
            val dest = File(context.filesDir, ESPEAK)
            val marker = File(dest, ".copied")
            if (marker.isFile) return dest
            copyAssetDir(context, "$DIR/$ESPEAK", dest)
            marker.writeText("ok")
            return dest
        }

        private fun copyAssetDir(context: Context, path: String, dest: File) {
            val children = context.assets.list(path).orEmpty()
            if (children.isEmpty()) {
                dest.parentFile?.mkdirs()
                context.assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
                return
            }
            dest.mkdirs()
            for (c in children) copyAssetDir(context, "$path/$c", File(dest, c))
        }
    }
}

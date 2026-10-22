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

/**
 * Offline voice (PLAN 3.3, D-025, D-038) via sherpa-onnx. [voice] picks the engine (Piper / Supertonic) and speaker;
 * the built-in Piper "Alan" is in APK assets `tts/`, other voices are downloaded to `files/voices/` (see [Voices]).
 * espeak-ng-data must be a real folder, so it is copied from assets to app files once (all engines share it).
 */
class TextToSpeech(
    context: Context,
    val voice: Voice = Voices.byId(Voices.BUILT_IN),
    threads: Int = 2,
    speed: Float = 1.0f,
) : AutoCloseable {
    private val tts: OfflineTts
    @Volatile private var stopped = false
    /** 1.0 = normal; can change between sentences without reloading. */
    @Volatile var speed = speed

    init {
        val data = copyEspeakData(context).path
        val pack = voice.pack
        val builtIn = pack.id == Voices.BUILT_IN
        fun f(name: String) = if (builtIn) "$DIR/$name" else File(Voices.dir(context, pack), name).path
        val model = when (pack.engine) {
            Engine.PIPER -> OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(model = f(pack.model), tokens = f("tokens.txt"), dataDir = data),
                numThreads = threads,
            )
            Engine.SUPERTONIC -> OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = f("duration_predictor.int8.onnx"), textEncoder = f("text_encoder.int8.onnx"),
                    vectorEstimator = f("vector_estimator.int8.onnx"), vocoder = f("vocoder.int8.onnx"),
                    ttsJson = f("tts.json"), unicodeIndexer = f("unicode_indexer.bin"), voiceStyle = f("voice.bin"),
                ),
                numThreads = threads,
            )
        }
        tts = OfflineTts(if (builtIn) context.assets else null, OfflineTtsConfig(model = model))
        generate("Hi.")   // first run is ~4x slower; pay it now, not on the first answer
    }

    fun generate(text: String): GeneratedAudio {
        val start = System.currentTimeMillis()
        val audio = if (voice.pack.engine == Engine.SUPERTONIC) {
            tts.generateWithConfig(text, GenerationConfig(sid = voice.sid, speed = speed, numSteps = SUPERTONIC_STEPS, extra = mapOf("lang" to "en")))
        } else {
            tts.generate(text, sid = voice.sid, speed = speed)
        }
        val made = System.currentTimeMillis() - start
        JarvisLog.i("tts ${voice.id}: ${audio.samples.size * 1000L / audio.sampleRate.coerceAtLeast(1)} ms audio in $made ms")
        return audio
    }

    /** Stops the sentence being played now (user tapped stop / said "stop"). */
    fun stop() { stopped = true }

    /** Blocks until the sentence has been played or [stop] is called. Returns false if stopped. */
    fun speak(text: String): Boolean {
        stopped = false
        val audio = generate(text)
        if (stopped) return false
        return play(audio)
    }

    /** Plays audio made by [generate] (the service makes the next sentence while this one plays). */
    fun play(audio: GeneratedAudio): Boolean {
        stopped = false
        return playPcm(audio.samples, audio.sampleRate) { stopped }
    }

    override fun close() = tts.release()

    companion object {
        private const val DIR = "tts"
        /** Supertonic quality/speed: more denoising steps = cleaner voice, slower. */
        private const val SUPERTONIC_STEPS = 3
        private const val ESPEAK = "espeak-ng-data"

        /** Quiet audio added after each sentence so the last sound is not cut when the track stops. */
        private const val TAIL_MS = 120

        /**
         * Plays mono float PCM on the assistant audio stream; blocks until done or [stop] returns true.
         * Waits on the real playback position (not the clock): on MIUI a track can start 200+ ms late,
         * and a clock-based wait then stopped it before the last words were heard.
         */
        fun playPcm(pcm: FloatArray, rate: Int, stop: () -> Boolean = { false }): Boolean {
            if (pcm.isEmpty()) return true
            val samples = pcm.copyOf(pcm.size + rate * TAIL_MS / 1000)
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
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(samples.size * 4)
                .build()
            try {
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                track.play()
                // give up only if playback is stuck (2 s more than the audio length)
                val end = System.currentTimeMillis() + samples.size * 1000L / rate + 2000
                while (!stop() && track.playbackHeadPosition < samples.size && System.currentTimeMillis() < end) {
                    Thread.sleep(20)
                }
                track.stop()
                return !stop()
            } finally {
                track.release()
            }
        }

        private fun copyEspeakData(context: Context): File {
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

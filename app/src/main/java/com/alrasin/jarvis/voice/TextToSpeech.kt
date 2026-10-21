package com.alrasin.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * Offline voice (PLAN 3.3, D-025): Piper "en_GB-alan-medium" via sherpa-onnx, model from APK assets `tts/`.
 * espeak-ng-data must be a real folder, so it is copied from assets to app files once.
 * [modelDir] (debug): load a Piper voice folder (one .onnx + tokens.txt) from storage instead of assets.
 */
class TextToSpeech(context: Context, modelDir: File? = null, threads: Int = 2) : AutoCloseable {
    private val tts: OfflineTts

    init {
        val data = copyEspeakData(context)
        val model = modelDir?.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
        tts = OfflineTts(
            if (modelDir == null) context.assets else null,
            OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = model?.path ?: "$DIR/en_GB-alan-medium.onnx",
                        tokens = if (modelDir == null) "$DIR/tokens.txt" else File(modelDir, "tokens.txt").path,
                        dataDir = data.path,
                    ),
                    numThreads = threads,
                ),
            ),
        )
        generate("Hi.")   // first run is ~4x slower; pay it now, not on the first answer
    }

    fun generate(text: String) = tts.generate(text, sid = 0, speed = 1.0f)

    /** Blocks until the sentence has been played. */
    fun speak(text: String) {
        val audio = generate(text)
        if (audio.samples.isEmpty()) return
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
                    .setSampleRate(audio.sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(audio.samples.size * 4)
            .build()
        try {
            track.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
            track.play()
            val ms = audio.samples.size * 1000L / audio.sampleRate
            Thread.sleep(ms + 150)
            track.stop()
        } finally {
            track.release()
        }
    }

    override fun close() = tts.release()

    companion object {
        private const val DIR = "tts"
        private const val ESPEAK = "espeak-ng-data"

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

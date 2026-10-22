package com.alrasin.jarvis.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import com.alrasin.jarvis.core.JarvisLog

/**
 * Synthesized cues loaded before the mic loop starts. Wake playback must not wait for synthesis,
 * track creation, TTS, or a queued previous cue. Owned by the mic loop thread.
 */
class Chimes : AutoCloseable {
    private val wake = prepare(ChimeSynth.wake())
    private val listen = prepare(ChimeSynth.listen())
    private val endListening = prepare(ChimeSynth.endListening())
    private val done = prepare(ChimeSynth.done())
    private val error = prepare(ChimeSynth.error())
    private var current: AudioTrack? = null

    fun wake() = play(wake, "wake")
    fun listen() = play(listen, "listen")
    fun endListening() = play(endListening, "end listening")
    fun done() = play(done, "done")
    fun error() = play(error, "error")

    private fun prepare(pcm: FloatArray): AudioTrack? = runCatching {
        val samples = ShortArray(pcm.size) { (pcm[it].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort() }
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(ChimeSynth.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setBufferSizeInBytes(samples.size * 2)
            .build()
        try {
            check(track.write(samples, 0, samples.size) == samples.size) { "Incomplete chime buffer" }
            track
        } catch (t: Throwable) {
            track.release()
            throw t
        }
    }.onFailure { JarvisLog.e("chime preparation failed", it) }.getOrNull()

    private fun play(track: AudioTrack?, name: String) {
        if (track == null) return
        val requested = SystemClock.elapsedRealtime()
        runCatching {
            current?.pause()
            track.pause()
            check(track.setPlaybackHeadPosition(0) == AudioTrack.SUCCESS)
            track.play()
            current = track
        }.onFailure { JarvisLog.e("$name chime failed", it) }
        JarvisLog.i("$name chime dispatch=${SystemClock.elapsedRealtime() - requested}ms (audible onset needs phone measurement)")
    }

    override fun close() {
        listOf(wake, listen, endListening, done, error).forEach { track ->
            runCatching { track?.release() }
        }
        current = null
    }
}

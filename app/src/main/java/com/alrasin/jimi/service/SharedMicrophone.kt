package com.alrasin.jimi.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.SystemClock
import com.alrasin.jimi.core.JimiLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** A silenced recording waits for Android to restore it without fighting the other assistant. */
internal class SharedMicrophone(
    private val context: Context,
    private val sampleRate: Int,
    /** Off when the software echo canceler runs: hardware AEC before it hurts "stop" (D-103). */
    private val hardwareEcho: Boolean = true,
    private val onAvailability: (Boolean) -> Unit,
) : AutoCloseable {
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    var echoCanceled = false
        private set
    private val retry = MicrophoneRetry()
    @Volatile private var silenced = false
    private var available: Boolean? = null
    private val reopen = SilencedReopenBackoff()
    private var loggedEcho: Boolean? = null
    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            val session = recorder?.audioSessionId ?: return
            configs.firstOrNull { it.clientAudioSessionId == session }?.let { silenced = it.isClientSilenced }
        }
    }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord {
        val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Microphone does not support 16 kHz mono PCM" }
        val rec = AudioRecord.Builder()
            // Not VOICE_COMMUNICATION: Google's scam guard reads that as a live call and then blocks
            // and nags about every accessibility service ("Can't complete action during call").
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum, sampleRate * 2))
            .setPrivacySensitive(false)
            .build()
        recorder = rec
        check(rec.state == AudioRecord.STATE_INITIALIZED) { "Microphone is unavailable" }
        echoCanceled = runCatching {
            echoCanceler = if (hardwareEcho && AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(rec.audioSessionId) else null
            echoCanceler?.enabled = true
            echoCanceler?.enabled == true
        }.onFailure { JimiLog.e("echo canceler unavailable", it) }.getOrDefault(false)
        if (loggedEcho != echoCanceled) {   // once per change, not on every reopen
            loggedEcho = echoCanceled
            JimiLog.i("barge-in echo canceler: $echoCanceled")
        }
        rec.registerAudioRecordingCallback(context.mainExecutor, callback)
        rec.startRecording()
        check(rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
        return rec
    }

    /** False means interrupted audio: callers must discard their partial command and wake buffer. */
    suspend fun read(chunk: ShortArray): Boolean {
        try {
            val rec = recorder ?: open()
            var got = 0
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (got < chunk.size) {
                currentCoroutineContext().ensureActive()
                if (rec.activeRecordingConfiguration?.isClientSilenced ?: silenced) {
                    setAvailable(false)
                    reopenIfSilencedLong()
                    delay(250)
                    return false
                }
                val count = rec.read(chunk, got, chunk.size - got, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "AudioRecord.read failed: $count" }
                if (count == 0) {
                    check(SystemClock.elapsedRealtime() < deadline) { "Microphone stopped delivering audio" }
                    delay(10)
                } else got += count
            }
            if (rec.activeRecordingConfiguration?.isClientSilenced ?: silenced) {
                setAvailable(false)
                return false
            }
            retry.recovered()
            if (reopen.waiting) JimiLog.i("Microphone back")
            reopen.recovered()
            setAvailable(true)
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            JimiLog.e("Microphone interrupted; waiting to retry", failure)
            setAvailable(false)
            close()
            delay(retry.nextDelayMillis())
            return false
        }
    }

    /**
     * MIUI can keep a recorder silenced after the other app frees the mic; a fresh recorder gets it back.
     * Reopens back off (3 s up to 30 s) so a long hold by another app does not churn the mic.
     */
    private fun reopenIfSilencedLong() {
        if (!reopen.waiting) JimiLog.i("Microphone held by another app; retrying with backoff")
        if (reopen.shouldReopen(SystemClock.elapsedRealtime())) close()
    }

    private fun setAvailable(value: Boolean) {
        if (available != value) {
            available = value
            onAvailability(value)
        }
    }

    fun releaseForPhone() {
        close()
        reopen.recovered()
        available = null
    }

    override fun close() {
        echoCanceled = false
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        val rec = recorder ?: return
        recorder = null
        runCatching { rec.unregisterAudioRecordingCallback(callback) }
        runCatching { rec.stop() }
        rec.release()
        silenced = false
    }
}

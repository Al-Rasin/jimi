package com.alrasin.jimi.voice

import com.alrasin.jimi.core.JimiLog

/**
 * WebRTC AEC3 (D-103): removes Jimi's own voice from the mic using what is playing as the reference, so a
 * spoken "stop" can be heard over loud speech. Native lib built by tools/fetch_aec.sh; [create] returns null
 * without it and barge-in falls back to quiet-playback-only stop detection.
 */
class EchoCanceller private constructor(private var handle: Long) {
    private val cleaned = ShortArray(PlaybackReference.CHUNK)

    /** Cleans one 16 kHz mic chunk; [far] is the matching playback reference. Reuses one output array. */
    fun process(far: ShortArray, mic: ShortArray): ShortArray {
        if (handle == 0L || mic.size != cleaned.size) return mic
        nativeProcess(handle, far, mic, cleaned)
        return cleaned
    }

    fun close() {
        if (handle != 0L) nativeDestroy(handle)
        handle = 0L
    }

    companion object {
        fun create(): EchoCanceller? = runCatching {
            System.loadLibrary("jimi_aec")
            nativeCreate(false).takeIf { it != 0L }?.let(::EchoCanceller)
        }.onFailure { JimiLog.i("echo canceler not loaded (run tools/fetch_aec.sh): ${it.message}") }.getOrNull()

        @JvmStatic private external fun nativeCreate(noiseSuppression: Boolean): Long
        @JvmStatic private external fun nativeProcess(handle: Long, far: ShortArray, mic: ShortArray, out: ShortArray)
        @JvmStatic private external fun nativeDestroy(handle: Long)
    }
}

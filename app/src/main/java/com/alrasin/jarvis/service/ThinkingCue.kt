package com.alrasin.jarvis.service

/** Accessed under the service's speech-state lock; playback stays on the chime owner's thread. */
internal class ThinkingCue {
    private var epoch: Int? = null
    private var startedAt = 0L

    fun start(epoch: Int, now: Long) {
        this.epoch = epoch
        startedAt = now
    }

    fun finish(epoch: Int) {
        if (this.epoch == epoch) this.epoch = null
    }

    fun take(now: Long, currentEpoch: Int, thinking: Boolean): Boolean {
        if (epoch == null) return false
        if (epoch != currentEpoch || !thinking) {
            epoch = null
            return false
        }
        if (now - startedAt < 6_000L) return false
        epoch = null
        return true
    }
}

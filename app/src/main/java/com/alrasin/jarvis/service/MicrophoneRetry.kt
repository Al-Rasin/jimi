package com.alrasin.jarvis.service

internal class MicrophoneRetry {
    private var failures = 0

    fun nextDelayMillis(): Long {
        val delay = (1_000L shl failures).coerceAtMost(15_000L)
        failures = (failures + 1).coerceAtMost(4)
        return delay
    }

    fun recovered() { failures = 0 }
}

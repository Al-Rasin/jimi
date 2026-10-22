package com.alrasin.jarvis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

enum class SpeechEngine(val label: String) {
    JIMI_OFFLINE("Jimi offline"),
    PHONE_ON_DEVICE("Phone (Google): online when available");

    companion object {
        fun fromStored(value: String?) = entries.firstOrNull { it.name == value } ?: JIMI_OFFLINE
    }
}

class SpeechEngineFallback(private val timeoutMillis: Long = 22_000) {
    suspend fun transcript(
        engine: SpeechEngine,
        sherpaText: String,
        phone: suspend () -> String?,
    ): String {
        if (engine == SpeechEngine.JIMI_OFFLINE) return sherpaText
        return try {
            withTimeoutOrNull(timeoutMillis) { phone() }?.takeIf { it.isNotBlank() } ?: sherpaText
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            sherpaText
        }
    }
}

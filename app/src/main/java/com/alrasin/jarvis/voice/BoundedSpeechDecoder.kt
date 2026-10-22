package com.alrasin.jarvis.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors

/** Native inference cannot be interrupted; only its waiting caller has a deadline. */
internal class BoundedSpeechDecoder(
    private val decode: (ShortArray) -> String,
    private val release: () -> Unit,
    private val deadlineMillis: Long = 1_500,
    private val prepare: (() -> Unit)? = null,
    private val onFallback: (String) -> Unit = {},
) : AutoCloseable {
    private val gate = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "jimi-stt-second-pass").apply { isDaemon = true }
    }
    private var busy = false
    private var closed = false
    private var ready = prepare == null

    suspend fun refine(audio: ShortArray, fallback: String, enabled: Boolean): String {
        if (!enabled || audio.isEmpty() || fallback.isBlank()) return fallback
        val result = CompletableDeferred<Result<String>>()
        synchronized(gate) {
            if (closed || busy) {
                onFallback(if (closed) "disabled after failure, timeout or close" else "worker busy")
                return fallback
            }
            busy = true
            if (!ready) {
                worker.execute {
                    val loaded = runCatching { prepare?.invoke() }.isSuccess
                    synchronized(gate) {
                        ready = loaded
                        busy = false
                        if (!loaded) {
                            onFallback("model load failed")
                            close()
                        }
                    }
                }
                onFallback("model warming")
                return fallback
            }
            worker.execute {
                val decoded = runCatching { decode(audio) }
                synchronized(gate) { busy = false }
                result.complete(decoded)
            }
        }
        val decoded = try {
            withTimeoutOrNull(deadlineMillis) { result.await() }
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
        if (decoded == null || decoded.isFailure) {
            onFallback(if (decoded == null) "decode deadline exceeded" else "decode failed")
            close()
            return fallback
        }
        return decoded.getOrNull()?.takeIf { it.isNotBlank() } ?: fallback.also { onFallback("empty result") }
    }

    override fun close() = synchronized(gate) {
        if (!closed) {
            closed = true
            // Queuing release prevents freeing a recognizer still inside native decode.
            worker.execute { release() }
            worker.shutdown()
        }
    }
}

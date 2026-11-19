package com.alrasin.jarvis.core

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but a coroutine cancel is thrown again instead of being turned into a failure.
 * Use it inside suspend code so "stop" really stops.
 */
inline fun <T> catching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

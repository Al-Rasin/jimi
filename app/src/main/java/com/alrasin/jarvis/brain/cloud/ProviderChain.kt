package com.alrasin.jarvis.brain.cloud

import com.alrasin.jarvis.brain.CloudProvider
import com.alrasin.jarvis.brain.Exchange
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Cloud models tried in order (D-063, replaces the 2-step fallback of D-055). On an error the next one answers.
 * A model that hit its free limit (HTTP 429) is skipped for [restMs]. A stream that already gave text is not
 * retried, so nothing is spoken twice.
 */
class ProviderChain(
    val providers: List<CloudProvider>,
    private val restMs: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) : CloudProvider {
    override val name = providers.joinToString("+") { it.name }
    private val restUntil = LongArray(providers.size)

    override suspend fun chat(system: String, history: List<Exchange>, text: String) =
        each { p, _ -> p.chat(system, history, text) }

    override suspend fun quick(system: String, history: List<Exchange>, text: String, maxTokens: Int) =
        each { p, _ -> p.quick(system, history, text, maxTokens) }

    override suspend fun stream(system: String, history: List<Exchange>, text: String, maxTokens: Int, onText: (String) -> Unit) =
        each { p, gave -> p.stream(system, history, text, maxTokens) { piece -> gave(); onText(piece) } }

    override fun warm() { order().firstOrNull()?.let { providers[it].warm() } }

    /** Indices of models not resting; all of them if every one is resting. */
    private fun order(): List<Int> = providers.indices.filter { restUntil[it] <= now() }.ifEmpty { providers.indices.toList() }

    /** [call] gets the model and a "text was given" marker; once text was given, an error is not retried. */
    private suspend fun each(call: suspend (CloudProvider, () -> Unit) -> String): String {
        var last: Exception? = null
        for (i in order()) {
            var gave = false
            try {
                val out = call(providers[i]) { gave = true }
                if (out.isNotBlank() || gave) return out
                last = IllegalStateException("${providers[i].name}: empty answer")
                JarvisLog.i("cloud ${providers[i].name} gave an empty answer, trying next")
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (e is CancellationException || gave) throw e
                last = e
                if (e is CloudHttpException && e.code == 429) restUntil[i] = now() + restMs
                JarvisLog.e("cloud ${providers[i].name} failed, trying next", e)
            }
        }
        throw last ?: IllegalStateException("no cloud model")
    }
}

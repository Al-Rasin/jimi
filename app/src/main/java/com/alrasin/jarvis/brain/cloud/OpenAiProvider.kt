package com.alrasin.jarvis.brain.cloud

import com.alrasin.jarvis.brain.CloudProvider
import com.alrasin.jarvis.brain.Exchange
import com.alrasin.jarvis.core.Json

/**
 * One OpenAI-style chat service (D-063 §L.6). [extra] goes into every request body (e.g. thinking off).
 * [thinkTokens] is added to max_tokens for models that think before answering, so the answer is not cut off.
 */
data class Preset(
    val label: String,
    val url: String,
    val model: String,
    val extra: Map<String, Any> = emptyMap(),
    val thinkTokens: Int = 0,
) {
    companion object {
        /** Free: 30 requests/min, 1,000/day per model (2026-10). */
        val GROQ = Preset(
            "Groq", "https://api.groq.com/openai/v1/chat/completions", "openai/gpt-oss-120b",
            mapOf("reasoning_effort" to "low", "include_reasoning" to false), thinkTokens = 400,
        )

        /** Free router: picks a free model by itself (names of free models change often). */
        val OPENROUTER = Preset(
            "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", "openrouter/free",
            mapOf("reasoning" to mapOf("exclude" to true)), thinkTokens = 400,
        )

        /** Free: 10,000 "neurons" a day. The URL needs the account ID. */
        fun cloudflare(accountId: String) = Preset(
            "Cloudflare", "https://api.cloudflare.com/client/v4/accounts/${accountId.trim()}/ai/v1/chat/completions",
            "@cf/meta/llama-3.3-70b-instruct-fp8-fast",
        )
    }
}

/** Any OpenAI-style chat API: Groq, OpenRouter, Cloudflare … (D-063). The key is never logged. */
class OpenAiProvider(private val preset: Preset, private val apiKey: String) : CloudProvider {
    override val name = preset.label
    private val headers = mapOf("Authorization" to "Bearer $apiKey")

    override suspend fun chat(system: String, history: List<Exchange>, text: String): String = quick(system, history, text, 1024)

    override suspend fun quick(system: String, history: List<Exchange>, text: String, maxTokens: Int): String =
        CloudHttp.call(client, post(system, history, text, maxTokens, stream = false), name, ::parse)

    override suspend fun stream(system: String, history: List<Exchange>, text: String, maxTokens: Int, onText: (String) -> Unit): String =
        CloudHttp.stream(client, post(system, history, text, maxTokens, stream = true), name, ::sseText, onText)

    override fun warm() = CloudHttp.warm(client, preset.url.substringBefore("/chat/completions") + "/models", headers)

    private fun post(system: String, history: List<Exchange>, text: String, maxTokens: Int, stream: Boolean) =
        CloudHttp.post(preset.url, headers, request(preset, system, history, text, maxTokens, stream))

    companion object {
        private val client by lazy { CloudHttp.client(5, 20) }

        fun request(preset: Preset, system: String, history: List<Exchange>, text: String, maxTokens: Int, stream: Boolean): String {
            val messages = listOf(msg("system", system)) +
                history.flatMap { listOf(msg("user", it.user), msg("assistant", it.reply)) } + msg("user", text)
            return Json.write(
                mapOf(
                    "model" to preset.model, "messages" to messages, "max_tokens" to maxTokens + preset.thinkTokens,
                    "stream" to stream,
                ) + preset.extra,
            )
        }

        fun parse(body: String): String {
            val root = Json.parse(body) as? Map<*, *> ?: error("bad JSON")
            val first = (root["choices"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: error("no answer")
            return ((first["message"] as? Map<*, *>)?.get("content") as? String).orEmpty().trim()
        }

        /** Text of one SSE line ("data: {choices:[{delta:{content}}]}"), or null for blank / [DONE] / no-text lines. */
        fun sseText(line: String): String? {
            if (!line.startsWith("data:")) return null
            val data = line.removePrefix("data:").trim()
            if (data == "[DONE]") return null
            return runCatching {
                val first = ((Json.parse(data) as Map<*, *>)["choices"] as List<*>).first() as Map<*, *>
                (first["delta"] as? Map<*, *>)?.get("content") as? String
            }.getOrNull()?.ifEmpty { null }
        }

        private fun msg(role: String, content: String) = mapOf("role" to role, "content" to content)
    }
}

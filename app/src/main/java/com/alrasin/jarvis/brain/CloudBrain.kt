package com.alrasin.jarvis.brain

import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.llm.ChatFormat
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A cloud chat model (PLAN 14.1). Only plain talk goes here: tools always run locally. */
interface CloudProvider {
    val name: String
    suspend fun chat(system: String, history: List<Exchange>, text: String): String
}

/** Claude through the official Anthropic Java SDK. The API key comes from [Secrets] (Keystore), never logged. */
class ClaudeProvider(private val apiKey: String) : CloudProvider {
    override val name = "Claude"
    private val client: AnthropicClient by lazy { AnthropicOkHttpClient.builder().apiKey(apiKey).build() }

    override suspend fun chat(system: String, history: List<Exchange>, text: String): String = withContext(Dispatchers.IO) {
        val p = MessageCreateParams.builder()
            .model(MODEL)
            .maxTokens(2048L)
            .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())   // short spoken answers, low wait
            .system(system)
        history.forEach { p.addUserMessage(it.user).addAssistantMessage(it.reply) }
        p.addUserMessage(text)
        client.messages().create(p.build()).content()
            .flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }
            .joinToString(" ").trim()
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
    }
}

/**
 * Optional cloud (PLAN 14, D-005): used only when the setting is on AND a key is saved. Then it answers chat
 * ("think hard …" / "ask Claude …" always, other talk when [Settings.cloudForChat] is on). Any error → null,
 * and the local chat model answers instead. Facts about the user are sent only if [Settings.cloudSendFacts].
 */
@Singleton
class CloudBrain @Inject constructor(private val settings: Settings, private val secrets: Secrets) {
    private var provider: CloudProvider? = null
    private var providerKey: String? = null

    suspend fun enabled(): Boolean = settings.cloudEnabled.first() && secrets.has(Secrets.CLOUD_KEY)

    /** True if [text] asks for the cloud by name, e.g. "think hard about …". */
    fun asked(text: String) = ASK.containsMatchIn(text)

    suspend fun chat(text: String, history: List<Exchange>, facts: List<String>, forced: Boolean): String? {
        if (!enabled() || !(forced || settings.cloudForChat.first())) return null
        val p = provider() ?: return null
        val sendFacts = settings.cloudSendFacts.first()
        val system = Persona.SYSTEM_PROMPT + if (sendFacts && facts.isNotEmpty()) "\nFacts about the user: " + facts.take(8).joinToString("; ") else ""
        return runCatching { p.chat(system, history, ASK.replace(text, "").trim().ifEmpty { text }) }
            .onSuccess { JarvisLog.i("cloud (${p.name}) answered") }
            .onFailure { JarvisLog.e("cloud failed, using local", it) }
            .getOrNull()?.let(ChatFormat::clean)?.takeIf { it.isNotBlank() }
    }

    private fun provider(): CloudProvider? {
        val key = secrets.get(Secrets.CLOUD_KEY) ?: return null
        if (key != providerKey) { provider = ClaudeProvider(key); providerKey = key }
        return provider
    }

    companion object {
        private val ASK = Regex("^(?:think hard(?: about)?|ask (?:claude|the cloud)(?: about)?)\\b,?\\s*", RegexOption.IGNORE_CASE)
    }
}

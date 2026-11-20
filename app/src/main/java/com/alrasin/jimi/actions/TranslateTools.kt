package com.alrasin.jimi.actions

import com.alrasin.jimi.brain.TranslateText
import com.alrasin.jimi.core.JimiLog
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolResult
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Translate English ↔ Bangla (ROADMAP #37, §I.12) with ML Kit on-device. The two language models (~30 MB each)
 * download once with internet, then work offline. Cloud chat, when on, answers first (ToolRunner).
 */
fun translateTool(): Tool = SimpleTool(
    "translate",
    "Translate a word or sentence between English and Bangla",
    listOf(
        arg("text", "The words to translate"),
        arg("to", "Language to translate into", required = false, choices = listOf(TranslateText.EN, TranslateText.BN)),
    ),
) { call, _ ->
    val text = call.arg("text")?.let(TranslateText::clean).orEmpty()
    if (text.isEmpty()) return@SimpleTool ToolResult.fail("What should I translate?")
    val to = TranslateText.lang(call.arg("to")) ?: TranslateText.target(text, null)
    val out = MlKitTranslate.run(text, TranslateText.source(to), to)
        ?: return@SimpleTool ToolResult.fail(
            "I couldn't translate that. The first time, I need the internet once to download the ${TranslateText.name(to)} pack.",
        )
    ToolResult.ok("In ${TranslateText.name(to)}: $out", out)
}

internal object MlKitTranslate {
    private const val DOWNLOAD_MS = 120_000L
    private const val TRANSLATE_MS = 15_000L
    // One client per direction, kept open: making a new one each call cost a model load every time (D-093).
    private val clients = java.util.concurrent.ConcurrentHashMap<String, com.google.mlkit.nl.translate.Translator>()
    private val ready = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    suspend fun run(text: String, from: String, to: String): String? {
        val key = "$from>$to"
        // computeIfAbsent, not getOrPut: two first calls at once (reply sentences + final text) must not open two clients.
        val translator = clients.computeIfAbsent(key) {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build())
        }
        if (key !in ready) {
            val ok = withTimeoutOrNull(DOWNLOAD_MS) { await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build())) != null }
            if (ok != true) return null.also { JimiLog.i("translate: model not ready ($from→$to)") }
            ready += key
        }
        return (withTimeoutOrNull(TRANSLATE_MS) { await(translator.translate(text)) } as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun code(lang: String) = if (lang == TranslateText.BN) TranslateLanguage.BENGALI else TranslateLanguage.ENGLISH

    /** Task result, or null on failure (download blocked, offline, …). Unit result becomes a non-null marker. */
    private suspend fun <T> await(task: Task<T>): Any? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { if (cont.isActive) cont.resume(it ?: Unit) }
            .addOnFailureListener {
                JimiLog.i("translate failed: ${it.message}")
                if (cont.isActive) cont.resume(null)
            }
    }
}

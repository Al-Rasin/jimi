package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.TranslateText
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
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

private object MlKitTranslate {
    private const val DOWNLOAD_MS = 120_000L
    private const val TRANSLATE_MS = 15_000L

    suspend fun run(text: String, from: String, to: String): String? {
        val options = TranslatorOptions.Builder().setSourceLanguage(code(from)).setTargetLanguage(code(to)).build()
        val translator = Translation.getClient(options)
        return try {
            val ready = withTimeoutOrNull(DOWNLOAD_MS) { await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build())) != null }
            if (ready != true) return null.also { JarvisLog.i("translate: model not ready ($from→$to)") }
            (withTimeoutOrNull(TRANSLATE_MS) { await(translator.translate(text)) } as? String)?.trim()?.takeIf { it.isNotEmpty() }
        } finally {
            translator.close()
        }
    }

    private fun code(lang: String) = if (lang == TranslateText.BN) TranslateLanguage.BENGALI else TranslateLanguage.ENGLISH

    /** Task result, or null on failure (download blocked, offline, …). Unit result becomes a non-null marker. */
    private suspend fun <T> await(task: Task<T>): Any? = suspendCancellableCoroutine { cont ->
        task.addOnSuccessListener { if (cont.isActive) cont.resume(it ?: Unit) }
            .addOnFailureListener {
                JarvisLog.i("translate failed: ${it.message}")
                if (cont.isActive) cont.resume(null)
            }
    }
}

package com.alrasin.jarvis.knowledge

import android.content.Context
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.voice.PortableModels

/**
 * One [Embedder] for the whole app (ROADMAP §K 11.3): wiki, web pages and memory facts share it, so the model is in
 * RAM once. Model files go in `models/embed/` (model.onnx + vocab.txt). No model → null, callers keep keyword ranking.
 * Runs fully on the phone: no root, no internet.
 */
object SharedEmbedder {
    private const val RECHECK_MS = 60_000L

    @Volatile private var loaded: Embedder? = null
    private var checkedAt = 0L

    fun get(context: Context): Embedder? {
        loaded?.let { return it }
        synchronized(this) {
            loaded?.let { return it }
            val now = System.currentTimeMillis()
            // Missing files: look again a minute later (the user may copy the model while the app runs).
            if (checkedAt != 0L && now - checkedAt < RECHECK_MS) return null
            checkedAt = now
            loaded = runCatching { Embedder.load(PortableModels.dir(context, "embed")) }
                .onFailure { JarvisLog.e("embedder load failed", it) }.getOrNull()
            return loaded
        }
    }

    /** Similarity of [query] to each text, or null when there is no model or it fails. */
    fun similarities(context: Context, query: String, texts: List<String>): FloatArray? {
        if (texts.isEmpty()) return null
        val e = get(context) ?: return null
        return runCatching { e.similarities(query, texts) }.onFailure { JarvisLog.e("embed failed", it) }.getOrNull()
    }
}

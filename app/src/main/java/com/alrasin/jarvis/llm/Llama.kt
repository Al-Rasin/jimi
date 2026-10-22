package com.alrasin.jarvis.llm

import com.alrasin.jarvis.core.JarvisLog
import java.io.File

/**
 * Thin JNI wrapper over llama.cpp (PLAN 9.1, D-019). One instance = one loaded GGUF model + context.
 * The KV cache keeps the longest common token prefix between calls, so a fixed system prompt is read once.
 * Not thread-safe: callers serialize (LocalBrain uses a Mutex).
 */
class Llama private constructor(private var handle: Long, val file: File) : AutoCloseable {

    fun interface OnPiece { fun piece(text: String): Boolean }   // return false to stop

    /** [grammar]: GBNF text or null. [stop]: generation ends when the output contains one of these. */
    fun generate(prompt: String, grammar: String?, maxTokens: Int, stop: List<String> = emptyList(), onPiece: OnPiece? = null): String {
        check(handle != 0L) { "model closed" }
        val sb = StringBuilder()
        nativeGenerate(handle, prompt, grammar, maxTokens, temperature = 0f) { p ->
            sb.append(p)
            val stopHit = stop.any { sb.endsWith(it) }
            (onPiece?.piece(p) ?: true) && !stopHit
        }
        var out = sb.toString()
        stop.forEach { s -> if (out.endsWith(s)) out = out.removeSuffix(s) }
        return out
    }

    override fun close() {
        if (handle != 0L) nativeFree(handle)
        handle = 0
    }

    companion object {
        val available: Boolean = runCatching { System.loadLibrary("jarvis_llm"); true }
            .onFailure { JarvisLog.i("local LLM library not built (run tools/fetch_llama.sh, then rebuild)") }
            .getOrDefault(false)

        fun load(file: File, contextSize: Int, threads: Int): Llama? {
            if (!available || !file.exists()) return null
            val t0 = System.currentTimeMillis()
            val h = nativeLoad(file.path, contextSize, threads)
            if (h == 0L) { JarvisLog.e("could not load ${file.name}"); return null }
            JarvisLog.i("loaded ${file.name} in ${System.currentTimeMillis() - t0} ms")
            return Llama(h, file)
        }

        @JvmStatic private external fun nativeLoad(path: String, nCtx: Int, threads: Int): Long
        @JvmStatic private external fun nativeGenerate(
            handle: Long, prompt: String, grammar: String?, maxTokens: Int, temperature: Float, cb: OnPiece,
        )
        @JvmStatic private external fun nativeFree(handle: Long)
    }
}

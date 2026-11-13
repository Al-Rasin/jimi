package com.alrasin.jarvis.llm

import java.io.File

object ChatModels {
    const val DEFAULT = "default"
    const val LFM = "lfm2.5-1.2b-instruct"
    const val LFM_FILE = "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf"
    /** Challenger for ROADMAP #72: compare with Qwen3 0.6B on the phone before choosing. */
    const val QWEN35 = "qwen3.5-0.8b"
    private const val QWEN35_PREFIX = "Qwen3.5-0.8B"

    val ALL = listOf(DEFAULT, LFM, QWEN35)

    fun isQwen35(name: String) = name.startsWith("Qwen3.5-", ignoreCase = true)

    fun findQwen35(dir: File): File? = dir.listFiles().orEmpty()
        .filter { it.isFile && it.name.startsWith(QWEN35_PREFIX, ignoreCase = true) && it.extension.equals("gguf", ignoreCase = true) }
        .minByOrNull { it.name }

    fun findLfm(dir: File): File? = dir.listFiles().orEmpty()
        .filter {
            it.isFile && it.name.startsWith("LFM2.5-1.2B-Instruct-", ignoreCase = true) &&
                it.extension.equals("gguf", ignoreCase = true)
        }
        .sortedWith(compareBy<File> { !it.name.equals(LFM_FILE, ignoreCase = true) }.thenBy { it.name })
        .firstOrNull()
}

package com.alrasin.jarvis.llm

import java.io.File

object ChatModels {
    const val DEFAULT = "default"
    const val LFM = "lfm2.5-1.2b-instruct"
    const val LFM_FILE = "LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf"

    fun findLfm(dir: File): File? = dir.listFiles().orEmpty()
        .filter {
            it.isFile && it.name.startsWith("LFM2.5-1.2B-Instruct-", ignoreCase = true) &&
                it.extension.equals("gguf", ignoreCase = true)
        }
        .sortedWith(compareBy<File> { !it.name.equals(LFM_FILE, ignoreCase = true) }.thenBy { it.name })
        .firstOrNull()
}

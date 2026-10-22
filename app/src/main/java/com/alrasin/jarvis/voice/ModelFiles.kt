package com.alrasin.jarvis.voice

import java.io.File

/** File checks shared by setup and the model loaders. */
object ModelFiles {
    fun present(file: File) = file.isFile && file.length() > 0

    fun voiceReady(dir: File): Boolean = listOf(
        "en_GB-alan-medium.onnx", "tokens.txt", "espeak-ng-data/phontab", ".complete",
    ).all { present(File(dir, it)) }

    fun speechReady(dir: File): Boolean = present(File(dir, "tokens.txt")) &&
        listOf("encoder", "decoder", "joiner").all { part ->
            dir.listFiles().orEmpty().any { it.name.startsWith(part) && it.extension == "onnx" && present(it) }
        }

    fun archivePath(name: String): String? {
        if (name.startsWith('/') || '\\' in name) return null
        val parts = name.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        return parts.drop(1).joinToString("/").takeIf { it.isNotEmpty() }
    }

    fun validateDownload(bytes: Long, expected: Long) {
        require(bytes > 0) { "The download was empty" }
        require(expected <= 0 || bytes == expected) { "The download was interrupted; try again" }
    }
}

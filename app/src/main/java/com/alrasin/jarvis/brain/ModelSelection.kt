package com.alrasin.jarvis.brain

import com.alrasin.jarvis.llm.ChatModels
import java.io.File

internal object ModelSelection {
    fun fileFor(route: IntentRouter.Route, dir: File, selection: String = ChatModels.DEFAULT): File? {
        if (route == IntentRouter.Route.WEB) return null
        if (route == IntentRouter.Route.CHAT && selection == ChatModels.LFM) {
            ChatModels.findLfm(dir)?.let { return it }
        }
        val prefixes = if (route == IntentRouter.Route.CHAT) listOf("qwen3", "gemma-3")
            else listOf("functiongemma")
        return dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            .sortedByDescending { it.name.contains("jarvis") }
            .firstOrNull { file -> prefixes.any { file.name.startsWith(it, ignoreCase = true) } }
    }
}

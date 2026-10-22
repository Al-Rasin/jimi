package com.alrasin.jarvis.voice

import android.content.Context
import java.io.File

/**
 * Finds the wake word ONNX models.
 * 1. `<external files>/models/wakeword/<name>` — push a retrained model here with adb, no rebuild needed.
 * 2. APK assets `wakeword/<name>` (from models/wakeword/app-assets at build time).
 * Only hey_jimi.onnx is accepted so setup never advertises a different wake word.
 */
object WakeModels {
    private const val DIR = "wakeword"
    val WAKE_NAMES = listOf("hey_jimi.onnx")

    class Loaded(val mel: ByteArray, val embedding: ByteArray, val wake: ByteArray, val wakeName: String)

    fun load(context: Context): Loaded {
        val mel = read(context, "melspectrogram.onnx") ?: error("melspectrogram.onnx missing")
        val emb = read(context, "embedding_model.onnx") ?: error("embedding_model.onnx missing")
        for (name in WAKE_NAMES) {
            read(context, name)?.let { return Loaded(mel, emb, it, name) }
        }
        error("no wake model (${WAKE_NAMES.joinToString()})")
    }

    fun overrideDir(context: Context): File = PortableModels.dir(context, DIR)

    private fun read(context: Context, name: String): ByteArray? {
        val f = File(overrideDir(context), name)
        if (ModelFiles.present(f)) return f.readBytes()
        return runCatching { context.assets.open("$DIR/$name").use { it.readBytes() } }.getOrNull()
    }
}

package com.alrasin.jarvis.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clipboard (ROADMAP #34): "copy that" puts Jarvis's last answer (or the screen text) on the clipboard,
 * "read what I copied" reads it back. Android blocks background clipboard reads, so root is the fallback.
 */
object ClipboardText {
    const val MAX_SAY = 400

    /** Last text Jarvis copied itself: still readable when Android hides the clipboard. */
    @Volatile var lastCopied: String = ""

    private val CLIP_T = Regex("\\{T:(.*)\\}", RegexOption.DOT_MATCHES_ALL)

    /** Output of `cmd clipboard get-primary-clip` → plain text, or null when it has none. */
    fun fromRoot(out: String): String? {
        val t = out.trim()
        if (t.isEmpty() || t.equals("null", true) || t.contains("Exception") || t.startsWith("Unknown command", true) ||
            t.contains("No shell command", true)
        ) return null
        val text = CLIP_T.find(t)?.groupValues?.get(1)?.trimEnd('}', ' ')?.trim() ?: t
        return text.ifBlank { null }
    }

    fun say(text: String): String {
        val one = text.replace(Regex("\\s+"), " ").trim()
        return if (one.length <= MAX_SAY) "Your clipboard says: $one" else "Your clipboard says: ${one.take(MAX_SAY)}… and more."
    }
}

fun clipboardTools(context: Context): List<Tool> {
    fun manager() = context.getSystemService(ClipboardManager::class.java)

    return listOf(
        SimpleTool(
            "copy_text",
            "Copy text to the clipboard: the given text, Jarvis's last answer, or the screen text",
            listOf(
                arg("text", "text to copy", required = false),
                arg("source", "what to copy when no text is given", required = false, choices = listOf("answer", "screen")),
            ),
        ) { c, _ ->
            val text = c.arg("text")?.takeIf { it.isNotBlank() } ?: if (c.arg("source") == "screen") {
                val acc = JarvisAccessibilityService.instance
                    ?: return@SimpleTool ToolResult.fail("Screen control is off. Open Jarvis setup to turn on accessibility.")
                acc.screenText().ifBlank { return@SimpleTool ToolResult.fail("I don't see any text on the screen.") }
            } else return@SimpleTool ToolResult.fail("Nothing to copy yet.")
            val ok = withContext(Dispatchers.Main) {
                runCatching { manager().setPrimaryClip(ClipData.newPlainText("Jarvis", text)) }.isSuccess
            }
            if (!ok) return@SimpleTool ToolResult.fail("I can't copy right now.")
            ClipboardText.lastCopied = text
            ToolResult.ok(if (c.arg("source") == "screen") "Copied the screen text." else "Copied.")
        },
        SimpleTool("read_clipboard", "Read what is on the clipboard") { _, _ ->
            val direct = withContext(Dispatchers.Main) {
                runCatching { manager().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() }
                    .getOrNull()?.takeIf { it.isNotBlank() }
            }
            val text = direct
                ?: RootShell.run("cmd clipboard get-primary-clip", timeoutMs = 3_000, log = false).takeIf { it.ok }?.let { ClipboardText.fromRoot(it.out) }
                ?: ClipboardText.lastCopied.ifBlank { null }
                ?: return@SimpleTool ToolResult.fail("I can't read the clipboard from the background.")
            ToolResult.ok(ClipboardText.say(text), text)
        },
    )
}

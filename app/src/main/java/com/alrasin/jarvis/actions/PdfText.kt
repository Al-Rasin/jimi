package com.alrasin.jarvis.actions

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Rough text from a small PDF (ROADMAP #35 "find my PDF about X"): inflates FlateDecode streams and keeps the
 * strings shown by Tj/TJ. Best try only: PDFs with custom font encodings give little text. Pure Kotlin: unit tested.
 */
object PdfText {
    private val STREAM = Regex("stream\\r?\\n")
    private val SHOWN = Regex("\\(((?:\\\\.|[^\\\\)])*)\\)\\s*(?:Tj|'|\")|\\[((?:[^\\]])*)\\]\\s*TJ")
    private val PART = Regex("\\(((?:\\\\.|[^\\\\)])*)\\)")

    fun extract(pdf: ByteArray, maxChars: Int = 20_000): String {
        val raw = String(pdf, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var from = 0
        while (out.length < maxChars) {
            val m = STREAM.find(raw, from) ?: break
            val start = m.range.last + 1
            val end = raw.indexOf("endstream", start).takeIf { it > 0 } ?: break
            from = end + 9
            val content = inflate(pdf, start, end - start) ?: raw.substring(start, end)
            shown(content, out)
        }
        return out.toString().replace(Regex("\\s+"), " ").trim().take(maxChars)
    }

    private fun shown(content: String, out: StringBuilder) {
        for (m in SHOWN.findAll(content)) {
            if (m.groupValues[1].isNotEmpty()) out.append(unescape(m.groupValues[1]))
            else PART.findAll(m.groupValues[2]).forEach { out.append(unescape(it.groupValues[1])) }
            out.append(' ')
        }
    }

    private fun unescape(s: String): String = s.replace("\\(", "(").replace("\\)", ")").replace("\\n", " ").replace("\\\\", "\\")

    private fun inflate(bytes: ByteArray, off: Int, len: Int): String? = runCatching {
        val inf = Inflater().apply { setInput(bytes, off, len) }
        val buf = ByteArray(8192)
        val bos = ByteArrayOutputStream()
        while (!inf.finished() && bos.size() < 2_000_000) {
            val n = inf.inflate(buf)
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
            bos.write(buf, 0, n)
        }
        inf.end()
        if (bos.size() == 0) null else bos.toString("ISO-8859-1")
    }.getOrNull()
}

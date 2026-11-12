package com.alrasin.jarvis.core

/**
 * Tiny JSON reader/writer (no Android classes, so unit tests run on the JVM).
 * Objects → Map<String, Any?> (insertion order kept), arrays → List, numbers → Double/Long, plus String/Boolean/null.
 */
object Json {
    fun parse(text: String): Any? = Reader(text).run { val v = value(); ws(); require(i == s.length) { "extra text at $i" }; v }

    /** First complete {…} object inside [text] (LLM output may have words around it). */
    fun firstObject(text: String): Any? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (k in start until text.length) {
            val c = text[k]
            if (inStr) {
                if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
                continue
            }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { parse(text.substring(start, k + 1)) }.getOrNull()
            }
        }
        return null
    }

    fun write(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v == Math.floor(v) && !v.isInfinite() && kotlin.math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v)
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); write(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) { if (!first) sb.append(','); first = false; write(sb, x) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        sb.append('"')
    }

    private class Reader(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else error("bad char '$c' at $i")
            }
        }
        fun lit(word: String, v: Any?): Any? { require(s.startsWith(word, i)) { "bad literal at $i" }; i += word.length; return v }
        fun num(): Any {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            val t = s.substring(start, i)
            return t.toLongOrNull() ?: t.toDouble()
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws()
                require(s[i++] == ':') { "':' expected at ${i - 1}" }
                m[k] = value(); ws()
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> error("',' or '}' expected at ${i - 1}") }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> error("',' or ']' expected at ${i - 1}") }
            }
        }
    }
}

// Typed reads for parsed JSON (ROADMAP §L.7): `root.obj("query").obj("pages")` instead of `as? Map<*, *>` chains.
// Each returns null when the key is missing or holds another type.

/** [this] as a JSON object, or null. */
fun Any?.asObj(): Map<*, *>? = this as? Map<*, *>

fun Map<*, *>?.obj(key: String): Map<*, *>? = this?.get(key) as? Map<*, *>

fun Map<*, *>?.list(key: String): List<*>? = this?.get(key) as? List<*>

fun Map<*, *>?.str(key: String): String? = this?.get(key) as? String

/** First element of a JSON array when it is an object. */
fun List<*>?.firstObj(): Map<*, *>? = this?.firstOrNull() as? Map<*, *>

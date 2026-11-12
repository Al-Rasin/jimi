package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.PageRanker
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.asObj
import com.alrasin.jarvis.core.obj
import com.alrasin.jarvis.core.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// Regexes built once, not on every call (ROADMAP §L.4).
private val SEARCH_RE = Regex("uddg=([^&]+)")
private val QUESTION_RE = Regex("(?i)https?://\\S+|www\\.\\S+")
private val GENERAL_READING_RE = Regex("[\\p{L}\\p{N}]+")
private val TITLE_RE = Regex("(?<=[.!?])\\s+")

/** Web tools (PLAN 11). Online only; each fails with a short spoken line when offline. */
class Web {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    private val quick by lazy { http.newBuilder().callTimeout(4, TimeUnit.SECONDS).build() }

    /** [fast] = 4 s limit, for small API answers that should come back in under 3 s (§J.8). */
    suspend fun get(url: String, userAgent: String = UA, fast: Boolean = false): String = withContext(Dispatchers.IO) {
        (if (fast) quick else http).newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use {
            if (!it.isSuccessful) throw HttpError(it.code)
            it.body!!.string()
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        /** Wikimedia asks API clients for an honest agent name; spoofed browser agents can get 403. */
        const val WIKI_UA = "JimiAssistant/1.0 (personal Android voice assistant; okhttp)"
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }

    class HttpError(val code: Int) : IllegalStateException("HTTP $code")
}

/** Spoken line for a failed web call. Logs the real cause so "can't reach the internet" is no longer a guess (§M.8). */
internal fun webFailure(what: String, e: Throwable): ToolResult {
    JarvisLog.e("$what failed", e)
    val name = what.replaceFirstChar { it.uppercase() }
    return when (e) {
        is java.net.UnknownHostException -> ToolResult.fail("I can't reach the internet right now.")
        is java.io.InterruptedIOException -> ToolResult.fail("$name is taking too long. Please try again.")
        is java.io.IOException -> ToolResult.fail("I can't reach $what right now.")
        is Web.HttpError -> ToolResult.fail("$name didn't answer (error ${e.code}). Please try again later.")
        else -> ToolResult.fail("I couldn't read the answer from $what.")
    }
}

fun webTools(
    web: Web,
    weather: FastWeather = FastWeather(),
    here: suspend () -> CurrentLocation.Fix? = { null },
    newsCache: AnswerCache<List<String>> = AnswerCache(TimeUnit.MINUTES.toMillis(30)),
    homeCity: suspend () -> String,
): List<Tool> {

    fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        val absolute = when {
            url.startsWith("//") -> "https:$url"
            "://" in url -> url
            else -> "https://$url"
        }
        return absolute.toHttpUrlOrNull()?.toString()
    }

    suspend fun readPage(url: String, question: String, generalReading: Boolean = false, title: String = ""): ToolResult {
        val doc = Jsoup.parse(web.get(url), url)
        doc.select("script,style,nav,header,footer,aside").remove()
        val text = doc.select("p, li").joinToString("\n") { it.text() }
        if (text.isBlank()) return ToolResult.fail("That page has no text I can read.")
        if (generalReading) {
            val excerpt = text.take(2400)
            return ToolResult.ok(excerpt.take(400), "$excerpt\nSource: $url")
        }
        val chunks = PageRanker.rank(question, text, url, doc.title().ifBlank { title }, topN = 3, maxChars = 2400)
        if (chunks.isEmpty()) {
            return ToolResult(false, "I couldn't find passages on that page that match your question.", "Source: $url")
        }
        return ToolResult.ok(chunks.first().text.take(400), chunks.joinToString("\n\n") { "${it.text}\nSource: ${it.sourceUrl}" })
    }

    suspend fun search(q: String): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(web.get("https://html.duckduckgo.com/html/?q=${Web.enc(q)}"))
        return doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst(".result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            SEARCH_RE.find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            val url = normalizeUrl(href) ?: return@mapNotNull null
            Triple(a.text(), r.selectFirst(".result__snippet")?.text().orEmpty(), url)
        }.filter { !it.third.contains("duckduckgo.com/y.js") }
    }

    return listOf(
        SimpleTool("weather", "Weather now or tomorrow; no place = where the user is now", listOf(arg("place", "city", false), arg("day", "today or tomorrow", false, listOf("today", "tomorrow")))) { c, _ ->
            val tomorrow = c.arg("day") == "tomorrow"
            val place = c.arg("place")
            val fix = if (place == null) here() else null
            if (fix != null) weather.at(fix.lat, fix.lon, fix.name ?: "your area", tomorrow)
            else weather.get(place ?: homeCity(), tomorrow)
        },
        SimpleTool("news", "Top news headlines", listOf(arg("topic", "topic", false))) { c, _ ->
            val topic = c.arg("topic")
            val key = topic?.lowercase(java.util.Locale.ROOT).orEmpty()
            fun answer(list: List<String>, lead: String = "Top headlines: ") =
                if (list.isEmpty()) ToolResult.fail("No news found.")
                else ToolResult.ok(lead + list.take(3).joinToString(". ") + ".", list.joinToString("\n") { "• $it" })
            newsCache.fresh(key)?.let { return@SimpleTool answer(it) }
            runCatching {
                val url = if (topic == null) "https://news.google.com/rss?hl=en-BD&gl=BD&ceid=BD:en"
                else "https://news.google.com/rss/search?q=${Web.enc(topic)}&hl=en-BD&gl=BD&ceid=BD:en"
                val doc = Jsoup.parse(web.get(url, fast = true), "", Parser.xmlParser())
                doc.select("item > title").map { it.text().substringBeforeLast(" - ") }.take(5)
            }.fold({ list ->
                if (list.isNotEmpty()) newsCache.put(key, list)
                answer(list)
            }, { e ->
                newsCache.stale(key, TimeUnit.DAYS.toMillis(1))?.let { (list, age) ->
                    JarvisLog.e("news failed, using cache", e)
                    answer(list, "I'm offline. Headlines ${AnswerCache.ageSay(age)}: ")
                } ?: webFailure("Google News", e)
            })
        },
        SimpleTool("web_search", "Search the web and read the top answer", listOf(arg("query", "what to search"))) { c, _ ->
            val q = c.require("query", "Search for what?")
            runCatching { search(q) }.fold({ res ->
                if (res.isEmpty()) ToolResult.fail("I found nothing for $q.")
                else {
                    val first = res.first()
                    val url = normalizeUrl(first.third)
                    val page = url?.let { runCatching { readPage(it, q, title = first.first) }.getOrNull() }
                    page?.takeIf { it.ok } ?: ToolResult.ok(
                        first.second.ifBlank { first.first }.take(400),
                        res.take(3).joinToString("\n\n") {
                            "Search snippet: ${it.first.take(200)}\n${it.second.take(550)}\nSource: ${normalizeUrl(it.third).orEmpty()}"
                        },
                    )
                }
            }, { webFailure("the web", it) })
        },
        SimpleTool("read_page", "Read the main text of a web page", listOf(arg("url", "page address"))) { c, ctx ->
            val rawUrl = c.require("url", "Which page?")
            val url = normalizeUrl(rawUrl) ?: return@SimpleTool ToolResult.fail("That isn't a valid web address.")
            val question = ctx.heard.replace(QUESTION_RE, "")
                .replace(rawUrl, "", ignoreCase = true).trim()
            val readingWords = setOf("read", "open", "summarize", "summary", "page", "web", "webpage", "website", "url", "address", "link", "aloud",
                "please", "can", "could", "would", "you", "me", "the", "this", "that", "a", "an", "to", "for", "of", "hey", "jimi")
            val generalReading = GENERAL_READING_RE.findAll(question).all { it.value.lowercase(java.util.Locale.ROOT) in readingWords }
            runCatching { readPage(url, question, generalReading) }.getOrElse { webFailure("that page", it) }
        },
        SimpleTool("wiki_lookup", "Short Wikipedia summary", listOf(arg("topic", "topic"))) { c, _ ->
            val topic = c.require("topic", "Look up what?")
            suspend fun wiki(url: String): Any? = Json.parse(
                runCatching { web.get(url, Web.WIKI_UA) }.getOrElse { if (it is Web.HttpError && it.code < 500) throw it else web.get(url, Web.WIKI_UA) },
            )
            runCatching {
                val s = wiki("https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=${Web.enc(topic)}") as List<*>
                val title = ((s.getOrNull(1) as? List<*>)?.firstOrNull() as? String) ?: return@SimpleTool ToolResult.fail("Wikipedia has nothing on $topic.")
                runCatching {
                    (wiki("https://en.wikipedia.org/api/rest_v1/page/summary/${Web.enc(title.replace(' ', '_'))}") as Map<*, *>)["extract"] as? String
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: wikiExtract(
                    wiki("https://en.wikipedia.org/w/api.php?action=query&prop=extracts&exintro=1&explaintext=1&redirects=1&format=json&titles=${Web.enc(title)}"),
                )
            }.fold({ t ->
                if (t.isBlank()) ToolResult.fail("Wikipedia has nothing on $topic.")
                else ToolResult.ok(t.split(TITLE_RE).take(2).joinToString(" "), t)
            }, { webFailure("Wikipedia", it) })
        },
    )
}

/** Intro text from the action=query&prop=extracts answer (fallback when the REST summary fails). */
internal fun wikiExtract(json: Any?): String {
    val pages = json.asObj().obj("query").obj("pages") ?: return ""
    return pages.values.firstNotNullOfOrNull { it.asObj().str("extract") }.orEmpty()
}

package com.alrasin.jarvis.actions

import com.alrasin.jarvis.brain.PageRanker
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Web tools (PLAN 11). Online only; each fails with a short spoken line when offline. */
class Web {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use {
            if (!it.isSuccessful) error("HTTP ${it.code}")
            it.body!!.string()
        }
    }

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    }
}

fun webTools(
    web: Web,
    weather: FastWeather = FastWeather(),
    here: suspend () -> CurrentLocation.Fix? = { null },
    homeCity: suspend () -> String,
): List<Tool> {
    val offline = ToolResult.fail("I can't reach the internet right now.")

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
            Regex("uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
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
            runCatching {
                val topic = c.arg("topic")
                val url = if (topic == null) "https://news.google.com/rss?hl=en-BD&gl=BD&ceid=BD:en"
                else "https://news.google.com/rss/search?q=${Web.enc(topic)}&hl=en-BD&gl=BD&ceid=BD:en"
                val doc = Jsoup.parse(web.get(url), "", Parser.xmlParser())
                doc.select("item > title").map { it.text().substringBeforeLast(" - ") }.take(5)
            }.fold({ list ->
                if (list.isEmpty()) ToolResult.fail("No news found.")
                else ToolResult.ok("Top headlines: " + list.take(3).joinToString(". ") + ".", list.joinToString("\n") { "• $it" })
            }, { offline })
        },
        SimpleTool("web_search", "Search the web and read the top answer", listOf(arg("query", "what to search"))) { c, _ ->
            val q = c.arg("query") ?: return@SimpleTool ToolResult.fail("Search for what?")
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
            }, { offline })
        },
        SimpleTool("read_page", "Read the main text of a web page", listOf(arg("url", "page address"))) { c, ctx ->
            val rawUrl = c.arg("url") ?: return@SimpleTool ToolResult.fail("Which page?")
            val url = normalizeUrl(rawUrl) ?: return@SimpleTool ToolResult.fail("That isn't a valid web address.")
            val question = ctx.heard.replace(Regex("(?i)https?://\\S+|www\\.\\S+"), "")
                .replace(rawUrl, "", ignoreCase = true).trim()
            val readingWords = setOf("read", "open", "summarize", "summary", "page", "web", "webpage", "website", "url", "address", "link", "aloud",
                "please", "can", "could", "would", "you", "me", "the", "this", "that", "a", "an", "to", "for", "of", "hey", "jimi")
            val generalReading = Regex("[\\p{L}\\p{N}]+").findAll(question).all { it.value.lowercase(java.util.Locale.ROOT) in readingWords }
            runCatching { readPage(url, question, generalReading) }.getOrElse { offline }
        },
        SimpleTool("wiki_lookup", "Short Wikipedia summary", listOf(arg("topic", "topic"))) { c, _ ->
            val topic = c.arg("topic") ?: return@SimpleTool ToolResult.fail("Look up what?")
            runCatching {
                val s = Json.parse(web.get("https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=${Web.enc(topic)}")) as List<*>
                val title = ((s.getOrNull(1) as? List<*>)?.firstOrNull() as? String) ?: return@SimpleTool ToolResult.fail("Wikipedia has nothing on $topic.")
                val sum = Json.parse(web.get("https://en.wikipedia.org/api/rest_v1/page/summary/${Web.enc(title.replace(' ', '_'))}")) as Map<*, *>
                sum["extract"] as? String ?: ""
            }.fold({ t ->
                if (t.isBlank()) ToolResult.fail("Wikipedia has nothing on $topic.")
                else ToolResult.ok(t.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" "), t)
            }, { offline })
        },
    )
}

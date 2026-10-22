package com.alrasin.jarvis.actions

import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

fun webTools(web: Web, homeCity: suspend () -> String): List<Tool> {
    val offline = ToolResult.fail("I can't reach the internet right now.")

    suspend fun search(q: String): List<Triple<String, String, String>> {
        val doc = Jsoup.parse(web.get("https://html.duckduckgo.com/html/?q=${Web.enc(q)}"))
        return doc.select(".result").mapNotNull { r ->
            val a = r.selectFirst(".result__a") ?: return@mapNotNull null
            var href = a.attr("href")
            Regex("uddg=([^&]+)").find(href)?.let { href = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
            Triple(a.text(), r.selectFirst(".result__snippet")?.text().orEmpty(), href)
        }.filter { !it.third.contains("duckduckgo.com/y.js") }
    }

    fun weatherWord(code: Int) = when (code) {
        0 -> "clear sky"; 1, 2 -> "partly cloudy"; 3 -> "cloudy"; 45, 48 -> "foggy"
        in 51..57 -> "drizzle"; in 61..67 -> "rain"; in 71..77 -> "snow"; in 80..82 -> "rain showers"
        in 95..99 -> "thunderstorms"; else -> "mixed weather"
    }

    return listOf(
        SimpleTool("weather", "Weather now or tomorrow", listOf(arg("place", "city", false), arg("day", "today or tomorrow", false, listOf("today", "tomorrow")))) { c, _ ->
            runCatching {
                val place = c.arg("place") ?: homeCity()
                val geo = Json.parse(web.get("https://geocoding-api.open-meteo.com/v1/search?count=1&name=${Web.enc(place)}")) as Map<*, *>
                val g = (geo["results"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return@SimpleTool ToolResult.fail("I don't know the place $place.")
                val w = Json.parse(
                    web.get(
                        "https://api.open-meteo.com/v1/forecast?latitude=${g["latitude"]}&longitude=${g["longitude"]}" +
                            "&current=temperature_2m,weather_code&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max&timezone=auto&forecast_days=2",
                    ),
                ) as Map<*, *>
                val d = w["daily"] as Map<*, *>
                fun dv(k: String, i: Int) = ((d[k] as List<*>)[i] as Number).toDouble()
                val name = g["name"]
                if (c.arg("day") == "tomorrow") {
                    "Tomorrow in $name: ${weatherWord(dv("weather_code", 1).toInt())}, ${dv("temperature_2m_min", 1).toInt()} to ${dv("temperature_2m_max", 1).toInt()} degrees, ${dv("precipitation_probability_max", 1).toInt()} percent chance of rain."
                } else {
                    val cur = w["current"] as Map<*, *>
                    "In $name it's ${(cur["temperature_2m"] as Number).toInt()} degrees and ${weatherWord((cur["weather_code"] as Number).toInt())}. " +
                        "High ${dv("temperature_2m_max", 0).toInt()}, ${dv("precipitation_probability_max", 0).toInt()} percent chance of rain."
                }
            }.fold({ ToolResult.ok(it) }, { offline })
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
                else ToolResult.ok(res.first().second.ifBlank { res.first().first }, res.take(5).joinToString("\n\n") { "${it.first}\n${it.second}\n${it.third}" })
            }, { offline })
        },
        SimpleTool("read_page", "Read the main text of a web page", listOf(arg("url", "page address"))) { c, _ ->
            val url = c.arg("url") ?: return@SimpleTool ToolResult.fail("Which page?")
            runCatching {
                val doc = Jsoup.parse(web.get(if (url.startsWith("http")) url else "https://$url"))
                doc.select("script,style,nav,header,footer,aside").remove()
                doc.select("p").map { it.text() }.filter { it.length > 60 }.joinToString("\n")
            }.fold({ t -> if (t.isBlank()) ToolResult.fail("That page has no text I can read.") else ToolResult.ok(t.take(400), t.take(6000)) }, { offline })
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

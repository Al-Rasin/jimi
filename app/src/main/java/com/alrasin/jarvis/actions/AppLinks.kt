package com.alrasin.jarvis.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.core.resolves
import java.net.URLEncoder

// Regexes built once, not on every call (ROADMAP §L.4).
private val TRAVEL_RE = Regex("walk")
private val TRAVEL_RE_2 = Regex("motor|two wheel|scooter")
private val TRAVEL_RE_3 = Regex("bike|bicycl|cycl")
private val HANDLE_RE = Regex("[A-Za-z0-9._]{2,40}")

/**
 * Deep links / intents table (ROADMAP §H.2): known links first, universal intents after.
 * Pure (unit tested); callers check each [Link] with resolveActivity and try the next one when it does not resolve.
 */
object AppLinks {
    const val VIEW = "android.intent.action.VIEW"
    const val SEARCH = "android.intent.action.SEARCH"
    const val WEB_SEARCH = "android.intent.action.WEB_SEARCH"
    /** SearchManager.QUERY; the same key works for ACTION_SEARCH and ACTION_WEB_SEARCH. */
    const val QUERY_EXTRA = "query"

    const val MAPS = "com.google.android.apps.maps"
    const val YOUTUBE = "com.google.android.youtube"
    const val YT_MUSIC = "com.google.android.apps.youtube.music"
    const val SPOTIFY = "com.spotify.music"
    const val PLAY_STORE = "com.android.vending"
    const val CHROME = "com.android.chrome"
    const val TELEGRAM = "org.telegram.messenger"
    const val INSTAGRAM = "com.instagram.android"

    /** [pkg] null = any app that handles it. [query] set = put it in the SearchManager.QUERY extra. */
    data class Link(val action: String, val uri: String?, val pkg: String?, val query: String? = null)

    enum class Travel(val code: String, val web: String) {
        DRIVE("d", "driving"), WALK("w", "walking"), BIKE("b", "bicycling"), TWO_WHEELER("l", "two-wheeler"),
    }

    /** Percent-encoding for a URI query part (space = %20, not +). */
    fun enc(s: String): String = URLEncoder.encode(s.trim(), "UTF-8").replace("+", "%20")

    private val SEARCH_LINKS: Map<String, (String) -> String> = mapOf(
        YOUTUBE to { q -> "https://www.youtube.com/results?search_query=${enc(q)}" },
        YT_MUSIC to { q -> "https://music.youtube.com/search?q=${enc(q)}" },
        SPOTIFY to { q -> "spotify:search:${enc(q)}" },
        PLAY_STORE to { q -> "market://search?q=${enc(q)}&c=apps" },
        MAPS to { q -> "geo:0,0?q=${enc(q)}" },
        CHROME to { q -> "https://www.google.com/search?q=${enc(q)}" },
    )

    /**
     * Ways to search [query] inside [pkg], best first: the app's own link, then (when [universal]) ACTION_SEARCH
     * and web search sent to that app. Callers turn [universal] off when a tested screen search exists.
     */
    fun search(pkg: String, query: String, universal: Boolean = true): List<Link> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val own = SEARCH_LINKS[pkg]?.let { Link(VIEW, it(q), pkg) }
        return listOfNotNull(own) + if (universal) listOf(Link(SEARCH, null, pkg, q), Link(WEB_SEARCH, null, pkg, q)) else emptyList()
    }

    /** Turn-by-turn navigation in Google Maps, then any map app. */
    fun navigate(place: String, mode: Travel = Travel.DRIVE): List<Link> {
        val p = place.trim()
        if (p.isEmpty()) return emptyList()
        val nav = "google.navigation:q=${enc(p)}&mode=${mode.code}"
        return listOf(Link(VIEW, nav, MAPS), Link(VIEW, nav, null)) + mapSearch(p)
    }

    /** Show [place] on a map: Google Maps, any geo: app, then the Maps web page. */
    /** Route preview with distance and time, without starting directions ("how far is X"). */
    fun route(place: String, mode: Travel = Travel.DRIVE): List<Link> {
        val p = place.trim()
        if (p.isEmpty()) return emptyList()
        val dir = "https://www.google.com/maps/dir/?api=1&destination=${enc(p)}&travelmode=${mode.web}"
        return listOf(Link(VIEW, dir, MAPS), Link(VIEW, dir, null)) + mapSearch(p)
    }

    /** "Gulshan 2 is about 6.3 km away in a straight line." */
    fun distanceSay(place: String, meters: Float): String {
        val d = when {
            meters < 1_000 -> "${(Math.round(meters / 50f) * 50).coerceAtLeast(50)} metres"
            meters < 10_000 -> String.format(java.util.Locale.ROOT, "%.1f km", meters / 1_000f)
            else -> "${Math.round(meters / 1_000f)} km"
        }
        return "${place.trim()} is about $d away in a straight line."
    }

    fun mapSearch(place: String): List<Link> {
        val p = place.trim()
        if (p.isEmpty()) return emptyList()
        val geo = "geo:0,0?q=${enc(p)}"
        return listOf(Link(VIEW, geo, MAPS), Link(VIEW, geo, null), Link(VIEW, "https://www.google.com/maps/search/?api=1&query=${enc(p)}", null))
    }

    /** "@rahim_99" → Telegram profile/chat, then t.me in any app. */
    fun telegram(user: String): List<Link> {
        val u = handle(user) ?: return emptyList()
        return listOf(Link(VIEW, "tg://resolve?domain=$u", TELEGRAM), Link(VIEW, "https://t.me/$u", null))
    }

    /** Instagram profile, then the web page. */
    fun instagram(user: String): List<Link> {
        val u = handle(user) ?: return emptyList()
        return listOf(Link(VIEW, "instagram://user?username=$u", INSTAGRAM), Link(VIEW, "https://www.instagram.com/$u/", null))
    }

    /** "drive"/"walking"/"bike"/"motorbike" said by the user → travel mode. */
    fun travel(word: String?): Travel = when {
        word == null -> Travel.DRIVE
        TRAVEL_RE.containsMatchIn(word) -> Travel.WALK
        TRAVEL_RE_2.containsMatchIn(word) -> Travel.TWO_WHEELER
        TRAVEL_RE_3.containsMatchIn(word) -> Travel.BIKE
        else -> Travel.DRIVE
    }

    /** A user name is letters, digits, dots and underscores only; anything else is not a handle. */
    private fun handle(user: String): String? =
        user.trim().removePrefix("@").replace(" ", "").takeIf { it.matches(HANDLE_RE) }
}

/** Opens the first [links] entry some activity handles (checked with resolveActivity first). Returns it, or null. */
fun Context.openFirst(links: List<AppLinks.Link>): AppLinks.Link? = links.firstOrNull { link ->
    val intent = Intent(link.action)
    link.uri?.let { intent.data = Uri.parse(it) }
    link.pkg?.let { intent.setPackage(it) }
    link.query?.let { intent.putExtra(AppLinks.QUERY_EXTRA, it) }
    resolves(intent) && launch(intent)
}

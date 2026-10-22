package com.alrasin.jarvis.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay
import java.util.Locale

internal enum class MediaApp(val key: String, val label: String, val pkg: String) {
    YOUTUBE("youtube", "YouTube", "com.google.android.youtube"),
    YOUTUBE_MUSIC("youtube_music", "YouTube Music", "com.google.android.apps.youtube.music"),
    SPOTIFY("spotify", "Spotify", "com.spotify.music");

    companion object {
        fun from(value: String): MediaApp? = when (value.lowercase(Locale.ROOT).trim()) {
            "youtube", "you tube" -> YOUTUBE
            "youtube_music", "youtube music", "you tube music", "music" -> YOUTUBE_MUSIC
            "spotify" -> SPOTIFY
            else -> null
        }
    }
}

internal object MediaResultSelector {
    fun isResult(pkg: String, id: String, text: String, description: String): Boolean {
        val label = "$text $description".lowercase(Locale.ROOT).trim()
        if (label.isEmpty() || Regex("\\b(sponsored|advertisement|promoted)\\b").containsMatchIn(label)) return false
        val resource = id.substringAfter(":id/", "")
        return when (pkg) {
            MediaApp.YOUTUBE.pkg -> resource in setOf("video_title", "video_title_text", "video_title_text_view") ||
                (Regex("\\b(views|view|watching)\\b").containsMatchIn(label) &&
                    Regex("\\b\\d+ (second|minute|hour)s?\\b|\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(label))
            MediaApp.YOUTUBE_MUSIC.pkg -> resource in setOf("title", "song_title", "video_title") &&
                label !in setOf("songs", "videos", "albums", "playlists", "artists", "top result")
            MediaApp.SPOTIFY.pkg -> resource in setOf("track_name", "track_title", "row_title") &&
                label !in setOf("songs", "albums", "playlists", "artists", "top result")
            else -> false
        }
    }
}

internal suspend fun playSearch(context: Context, query: String, requestedApp: String?): ToolResult {
    val app = if (requestedApp == null) MediaApp.entries.firstOrNull { context.isInstalled(it.pkg) }
        ?: return ToolResult.fail("Install YouTube, YouTube Music or Spotify so I can play that.")
    else MediaApp.from(requestedApp) ?: return ToolResult.fail("Choose YouTube, YouTube Music or Spotify.")
    if (!context.isInstalled(app.pkg)) return ToolResult.fail("${app.label} is not installed.")
    val uri = when (app) {
        MediaApp.YOUTUBE -> "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        MediaApp.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=${Uri.encode(query)}"
        MediaApp.SPOTIFY -> "spotify:search:${Uri.encode(query)}"
    }
    val opened = context.launch(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(app.pkg)) ||
        context.launch(Intent(Intent.ACTION_SEARCH).setPackage(app.pkg).putExtra(SearchManager.QUERY, query))
    if (!opened) return ToolResult.fail("I couldn't open ${app.label} search.")
    val service = JarvisAccessibilityService.instance
        ?: return ToolResult.fail("I opened ${app.label} search. Enable Jimi accessibility so I can select the first result.")
    repeat(24) {
        delay(250)
        if (service.clickFirstMediaResult(app.pkg)) {
            return ToolResult.ok("I selected the first result for $query in ${app.label}. Playback requested, Boss.")
        }
    }
    return ToolResult.fail("I opened ${app.label} search but couldn't select a video or song. Unlock the phone and check the results and internet connection.")
}

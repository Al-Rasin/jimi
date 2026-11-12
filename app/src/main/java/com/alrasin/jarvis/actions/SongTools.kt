package com.alrasin.jarvis.actions

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.launch
import com.alrasin.jarvis.core.resolves

/**
 * Song ID (ROADMAP #38, §I.13, online only): "what song is this" opens the Google app's sound search, which
 * listens and shows the song. No own server and no audio leaves through Jarvis. Shazam is the fallback.
 */
object SongId {
    const val GOOGLE = "com.google.android.googlequicksearchbox"
    const val GOOGLE_MUSIC_SEARCH = "com.google.android.googlequicksearchbox.MUSIC_SEARCH"
    const val SHAZAM = "com.shazam.android"
    const val SHAZAM_TAG = "com.shazam.android.intent.actions.START_TAGGING"

    /** Intents to try, best first. */
    fun candidates(): List<Intent> = listOf(
        Intent(GOOGLE_MUSIC_SEARCH).setPackage(GOOGLE),
        Intent(GOOGLE_MUSIC_SEARCH),
        Intent(SHAZAM_TAG).setPackage(SHAZAM),
        Intent(Intent.ACTION_MAIN).setComponent(ComponentName(SHAZAM, "com.shazam.android.activities.MainActivity")),
    )
}

fun songTools(context: Context): List<Tool> = listOf(
    SimpleTool("identify_song", "Find the name of the song that is playing near the phone (needs internet)") { _, _ ->
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        if (!online) return@SimpleTool ToolResult.fail("Song search needs the internet, and the phone is offline.")
        val opened = SongId.candidates().firstOrNull { context.resolves(it) && context.launch(it) }
            ?: return@SimpleTool ToolResult.fail("I need the Google app or Shazam to find songs.")
        val who = if (opened.`package` == SongId.SHAZAM || opened.component?.packageName == SongId.SHAZAM) "Shazam" else "Google"
        ToolResult.ok("Listening with $who. Hold the phone near the music.")
    },
)

package com.alrasin.jarvis.actions

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import kotlinx.coroutines.delay

fun mediaTools(context: Context): List<Tool> {
    val audio by lazy { context.getSystemService(AudioManager::class.java) }
    fun musicActive(): Boolean? = runCatching { audio.isMusicActive }.getOrNull()
    suspend fun awaitMusic(active: Boolean): Boolean? {
        repeat(10) {
            delay(200)
            if (musicActive() == active) return active
        }
        return musicActive()
    }
    fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
    return listOf(
        SimpleTool("play_media", "Play a song, video or music", listOf(arg("query", "what to play"), arg("app", "youtube, spotify or music", required = false))) { c, _ ->
            val q = c.arg("query") ?: "music"
            val app = c.arg("app") ?: "youtube"
            val wasActive = musicActive()
            val ok = when {
                app.contains("spotify") && context.isInstalled("com.spotify.music") ->
                    context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(q)}")))
                app.contains("music") && !app.contains("youtube") ->
                    context.launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, q))
                context.isInstalled("com.google.android.youtube") ->
                    context.launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
                else -> context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")))
            }
            if (ok) MediaTruth.opened(wasActive, awaitMusic(true))
            else ToolResult.fail("I couldn't open a player, Boss.")
        },
        SimpleTool("media_control", "Pause, play, next or previous for music/video", listOf(arg("action", "what to do", choices = listOf("play", "pause", "next", "previous")))) { c, _ ->
            val action = c.arg("action")
            when (action) {
                "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "play" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
                "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                else -> return@SimpleTool ToolResult.fail("Play, pause, next or previous?")
            }
            MediaTruth.control(action!!, when (action) {
                "play" -> awaitMusic(true)
                "pause" -> awaitMusic(false)
                else -> null
            })
        },
        SimpleTool("set_volume", "Set media volume", listOf(arg("level", "0-100, up, or down"))) { c, _ ->
            val s = AudioManager.STREAM_MUSIC
            val max = audio.getStreamMaxVolume(s)
            when (val l = c.arg("level")) {
                "up" -> repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_RAISE, 0) }
                "down" -> repeat(2) { audio.adjustStreamVolume(s, AudioManager.ADJUST_LOWER, 0) }
                else -> {
                    val pct = l?.toDoubleOrNull()?.toInt()?.coerceIn(0, 100) ?: return@SimpleTool ToolResult.fail("What volume?")
                    audio.setStreamVolume(s, (pct * max + 50) / 100, 0)
                }
            }
            ToolResult.ok("Volume ${audio.getStreamVolume(s) * 100 / max.coerceAtLeast(1)} percent, Boss.")
        },
    )
}

internal object MediaTruth {
    fun opened(wasActive: Boolean?, active: Boolean?): ToolResult = ToolResult.ok(
        if (wasActive == false && active == true) "I opened it. Audio is playing, Boss."
        else "I opened it, Boss."
    )

    fun control(action: String, active: Boolean?): ToolResult = ToolResult.ok(when {
        action == "play" && active == true -> "Audio is playing, Boss."
        action == "pause" && active == false -> "No music is playing, Boss."
        action == "play" -> "Play requested, Boss. I couldn't confirm playback."
        action == "pause" -> "Pause requested, Boss. I couldn't confirm it."
        action == "next" -> "Next track requested, Boss."
        else -> "Previous track requested, Boss."
    })
}

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

fun mediaTools(context: Context): List<Tool> {
    val audio by lazy { context.getSystemService(AudioManager::class.java) }
    fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
    return listOf(
        SimpleTool("play_media", "Play a song, video or music", listOf(arg("query", "what to play"), arg("app", "youtube, spotify or music", required = false))) { c, _ ->
            val q = c.arg("query") ?: "music"
            val app = c.arg("app") ?: "youtube"
            val ok = when {
                app.contains("spotify") && context.isInstalled("com.spotify.music") ->
                    context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:${Uri.encode(q)}")))
                app.contains("music") && !app.contains("youtube") ->
                    context.launch(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).putExtra(SearchManager.QUERY, q))
                context.isInstalled("com.google.android.youtube") ->
                    context.launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
                else -> context.launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(q)}")))
            }
            if (ok) ToolResult.ok("Playing $q.") else ToolResult.fail("I couldn't open a player.")
        },
        SimpleTool("media_control", "Pause, play, next or previous for music/video", listOf(arg("action", "what to do", choices = listOf("play", "pause", "next", "previous")))) { c, _ ->
            when (c.arg("action")) {
                "pause" -> key(KeyEvent.KEYCODE_MEDIA_PAUSE)
                "play" -> key(KeyEvent.KEYCODE_MEDIA_PLAY)
                "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
                "previous" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
                else -> return@SimpleTool ToolResult.fail("Play, pause, next or previous?")
            }
            ToolResult.ok(when (c.arg("action")) { "pause" -> "Paused."; "play" -> "Playing."; "next" -> "Next."; else -> "Previous." })
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
            ToolResult.ok("Volume ${audio.getStreamVolume(s) * 100 / max} percent.")
        },
    )
}

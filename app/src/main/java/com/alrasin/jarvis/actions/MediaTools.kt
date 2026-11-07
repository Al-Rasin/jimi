package com.alrasin.jarvis.actions

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.pollUntil

fun mediaTools(context: Context): List<Tool> {
    val audio by lazy { context.getSystemService(AudioManager::class.java) }
    fun musicActive(): Boolean? = runCatching { audio.isMusicActive }.getOrNull()
    suspend fun awaitMusic(active: Boolean): Boolean? {
        if (pollUntil(10, 200, delayFirst = true) { musicActive() == active }) return active
        return musicActive()
    }
    fun key(code: Int) {
        val t = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
    return listOf(
        SimpleTool("play_media", "Search YouTube, YouTube Music or Spotify and select the first video or song to play",
            listOf(arg("query", "song, artist or video search text"), arg("app", "preferred installed player; omit to choose an installed player",
                required = false, choices = listOf("youtube", "youtube_music", "spotify")))) { c, _ ->
            val query = c.require("query", "What should I play?")
            playSearch(context, query, c.arg("app"))
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

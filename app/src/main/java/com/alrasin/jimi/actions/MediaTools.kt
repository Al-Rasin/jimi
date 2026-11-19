package com.alrasin.jimi.actions

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import com.alrasin.jimi.brain.Persona
import com.alrasin.jimi.core.Tool
import com.alrasin.jimi.core.ToolResult
import com.alrasin.jimi.core.pollUntil

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
        SimpleTool("media_control", "Pause, play, next, previous, jump forward/back, repeat or shuffle for music/video", listOf(
            arg("action", "what to do", choices = listOf("play", "pause", "next", "previous", "forward", "rewind", "repeat", "shuffle")),
            arg("seconds", "how far to jump for forward/rewind", required = false),
            arg("state", "for repeat: one, all or off; for shuffle: on or off", required = false),
        )) { c, _ ->
            val action = c.arg("action")
            when (action) {
                "forward", "rewind" -> {
                    val secs = c.arg("seconds")?.toDoubleOrNull()?.toInt()?.coerceIn(1, 3600) ?: 10
                    if (MediaSessions.seekBy(context, if (action == "forward") secs else -secs)) {
                        return@SimpleTool ToolResult.ok("${if (action == "forward") "Forward" else "Back"} ${speakDuration(secs)}, ${Persona.ADDRESS}.")
                    }
                    key(if (action == "forward") KeyEvent.KEYCODE_MEDIA_FAST_FORWARD else KeyEvent.KEYCODE_MEDIA_REWIND)
                    return@SimpleTool ToolResult.ok("I asked the player to ${if (action == "forward") "skip ahead" else "go back"}. I couldn't jump an exact time.")
                }
                "repeat" -> {
                    val state = c.arg("state") ?: "one"
                    val mode = when (state) { "off" -> 0; "all" -> 2; else -> 1 }
                    if (!MediaSessions.repeat(context, mode)) return@SimpleTool ToolResult.fail("No music player is open.")
                    return@SimpleTool ToolResult.ok(when (mode) { 0 -> "Repeat off."; 2 -> "Repeating the whole list."; else -> "Repeating this song." } +
                        " Some players ignore this, ${Persona.ADDRESS}.")
                }
                "shuffle" -> {
                    val on = c.arg("state") != "off"
                    if (!MediaSessions.shuffle(context, on)) return@SimpleTool ToolResult.fail("No music player is open.")
                    return@SimpleTool ToolResult.ok("Shuffle ${if (on) "on" else "off"}. Some players ignore this, ${Persona.ADDRESS}.")
                }
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
            ToolResult.ok("Volume ${audio.getStreamVolume(s) * 100 / max.coerceAtLeast(1)} percent, ${Persona.ADDRESS}.")
        },
    )
}

internal object MediaTruth {
    fun opened(wasActive: Boolean?, active: Boolean?): ToolResult = ToolResult.ok(
        if (wasActive == false && active == true) "I opened it. Audio is playing, ${Persona.ADDRESS}."
        else "I opened it, ${Persona.ADDRESS}."
    )

    fun control(action: String, active: Boolean?): ToolResult = ToolResult.ok(when {
        action == "play" && active == true -> "Audio is playing, ${Persona.ADDRESS}."
        action == "pause" && active == false -> "No music is playing, ${Persona.ADDRESS}."
        action == "play" -> "Play requested, ${Persona.ADDRESS}. I couldn't confirm playback."
        action == "pause" -> "Pause requested, ${Persona.ADDRESS}. I couldn't confirm it."
        action == "next" -> "Next track requested, ${Persona.ADDRESS}."
        else -> "Previous track requested, ${Persona.ADDRESS}."
    })
}

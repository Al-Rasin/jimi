package com.alrasin.jimi.actions

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.SystemClock
import com.alrasin.jimi.notify.JimiNotificationListener

/**
 * The phone's own players (Spotify, YouTube Music, ...) through their media sessions.
 * Needs notification access, which Jimi already has for reading notifications.
 */
internal object MediaSessions {
    // Players built on androidx media accept these instead of the missing framework calls.
    private const val COMPAT = "android.support.v4.media.session.action."

    fun active(context: Context): MediaController? = runCatching {
        val list = context.getSystemService(MediaSessionManager::class.java)
            .getActiveSessions(ComponentName(context, JimiNotificationListener::class.java))
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    }.getOrNull()

    /** "Title by Artist" of what is playing right now, or null. */
    fun nowPlaying(context: Context): String? {
        val c = active(context)?.takeIf { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return null
        val m = c.metadata ?: return null
        val title = m.getString(MediaMetadata.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val artist = (m.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.takeIf { it.isNotBlank() }
        return if (artist != null) "$title by $artist" else title
    }

    /** Jump [seconds] forward (negative = back). False when no player can seek. */
    fun seekBy(context: Context, seconds: Int): Boolean {
        val c = active(context) ?: return false
        val s = c.playbackState ?: return false
        if (s.actions and PlaybackState.ACTION_SEEK_TO == 0L) return false
        val drift = if (s.state == PlaybackState.STATE_PLAYING) ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong() else 0L
        val duration = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        c.transportControls.seekTo((s.position + drift + seconds * 1000L).coerceIn(0, duration))
        return true
    }

    /** Repeat this song (one), the list (all) or nothing (off). */
    fun repeat(context: Context, mode: Int): Boolean = custom(context, "SET_REPEAT_MODE", "ARGUMENT_REPEAT_MODE", mode)

    fun shuffle(context: Context, on: Boolean): Boolean = custom(context, "SET_SHUFFLE_MODE", "ARGUMENT_SHUFFLE_MODE", if (on) 1 else 0)

    private fun custom(context: Context, action: String, key: String, value: Int): Boolean {
        val c = active(context) ?: return false
        c.transportControls.sendCustomAction(COMPAT + action, Bundle().apply { putInt(COMPAT + key, value) })
        return true
    }
}

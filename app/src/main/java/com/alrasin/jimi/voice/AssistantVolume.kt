package com.alrasin.jimi.voice

import android.content.Context
import android.media.AudioManager
import com.alrasin.jimi.actions.RootShell
import com.alrasin.jimi.core.JimiLog

/**
 * Jimi speaks on the Assistant stream (11). MIUI shows no slider for it, so a volume-down press while Jimi talks
 * can leave it at 0 and every answer silent with no way to fix it in the UI. Before speaking, a muted Assistant
 * stream is raised to the media volume's share (at least 1 step).
 */
object AssistantVolume {
    private const val STREAM_ASSISTANT = 11

    suspend fun ensureAudible(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        val now = runCatching { audio.getStreamVolume(STREAM_ASSISTANT) }.getOrNull() ?: return
        if (now > 0) return
        val max = runCatching { audio.getStreamMaxVolume(STREAM_ASSISTANT) }.getOrDefault(15).coerceAtLeast(1)
        val target = level(audio.getStreamVolume(AudioManager.STREAM_MUSIC), audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), max)
        runCatching { audio.setStreamVolume(STREAM_ASSISTANT, target, 0) }
        if (audio.getStreamVolume(STREAM_ASSISTANT) == 0) {
            RootShell.run("cmd media_session volume --stream $STREAM_ASSISTANT --set $target", log = false)
        }
        JimiLog.i("Assistant volume was 0; set to $target/$max (now ${audio.getStreamVolume(STREAM_ASSISTANT)})")
    }

    /** Media volume scaled to the Assistant range; a muted media stream still gives half volume so Jimi is heard. */
    internal fun level(music: Int, musicMax: Int, max: Int): Int {
        if (music <= 0 || musicMax <= 0) return (max + 1) / 2
        return (music * max / musicMax).coerceIn(1, max)
    }
}

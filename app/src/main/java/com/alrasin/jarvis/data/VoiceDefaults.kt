package com.alrasin.jarvis.data

import com.alrasin.jarvis.voice.Voices

internal object VoiceDefaults {
    /** No saved voice: the Siri-like Google voice (D-054). */
    fun voice(saved: String?) = saved ?: Voices.DEFAULT
    fun speed(saved: Float?) = saved ?: 1.0f
}

package com.alrasin.jimi.data

import com.alrasin.jimi.voice.Voices

internal object VoiceDefaults {
    /** No saved voice: the Siri-like Google voice (D-054). */
    fun voice(saved: String?) = saved ?: Voices.DEFAULT
    fun speed(saved: Float?) = saved ?: 1.0f
}

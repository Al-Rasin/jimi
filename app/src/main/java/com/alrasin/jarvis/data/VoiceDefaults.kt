package com.alrasin.jarvis.data

internal object VoiceDefaults {
    fun voice(saved: String?, setupComplete: Boolean) = saved ?: if (setupComplete) "piper-alan" else "piper-amy"
    fun speed(saved: Float?, savedVoice: String?, setupComplete: Boolean) =
        saved ?: if (savedVoice != null || setupComplete) 1.0f else 1.10f
}

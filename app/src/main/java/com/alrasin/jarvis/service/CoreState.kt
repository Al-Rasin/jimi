package com.alrasin.jarvis.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Live state of the always-on core for the UI. Only [JarvisCoreService] writes the `_` flows. */
@Suppress("ObjectPropertyName")
object CoreState {
    const val PHASE_WAKE = "Say \"Hey Jimi\""
    const val PHASE_PREPARING = "Getting ready…"
    const val PHASE_HEARING = "Listening to you…"
    const val PHASE_SPEAKING = "Speaking…"
    const val PHASE_THINKING = "Thinking…"
    const val PHASE_FOLLOW_UP = "Anything else?"
    const val PHASE_MIC_BUSY = "Microphone busy or unavailable. Jimi will retry."

    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    internal val _microphoneAvailable = MutableStateFlow(false)
    val microphoneAvailable: StateFlow<Boolean> = _microphoneAvailable
    internal val _modelName = MutableStateFlow("")
    val modelName: StateFlow<String> = _modelName
    internal val _lastScore = MutableStateFlow(0f)
    val lastScore: StateFlow<Float> = _lastScore
    internal val _phase = MutableStateFlow(PHASE_WAKE)
    val phase: StateFlow<String> = _phase
    internal val _micLevel = MutableStateFlow(0f)
    /** Loudness 0..1 while hearing a command (panel animation). */
    val micLevel: StateFlow<Float> = _micLevel

    internal val _heard = MutableStateFlow("")
    /** Live (partial) text while hearing a command, then the final text. */
    val heard: StateFlow<String> = _heard
    internal val _reply = MutableStateFlow("")
    val reply: StateFlow<String> = _reply
    internal val _turnId = MutableStateFlow(0L)
    /** conversation_turn id of the last answer (for ✓ / ✗ in the panel). */
    val turnId: StateFlow<Long> = _turnId
    internal val _lastTurn = MutableStateFlow<Turn?>(null)
    /** The last finished turn, all fields from the same moment (panel chat bubbles). */
    val lastTurn: StateFlow<Turn?> = _lastTurn
    internal val _wakeId = MutableStateFlow<Long?>(null)
    /** wake_event id of the last voice wake (for "I didn't call"). */
    val wakeId: StateFlow<Long?> = _wakeId
    internal val _enrollStatus = MutableStateFlow("")
    val enrollStatus: StateFlow<String> = _enrollStatus
    internal val _lastVoiceScore = MutableStateFlow<Float?>(null)
    /** Voice ID score of the last locked command (to tune the threshold). */
    val lastVoiceScore: StateFlow<Float?> = _lastVoiceScore
}

/** One finished command: what was heard and what Jimi answered. */
data class Turn(val id: Long, val heard: String, val reply: String, val tool: String?)

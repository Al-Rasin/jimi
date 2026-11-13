package com.alrasin.jarvis.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the phone's situation turns on (ROADMAP B §I.11). Car = driving. */
data class ContextMode(val car: Boolean = false, val headphones: Boolean = false, val bedtime: Boolean = false) {
    /** New chat messages are read aloud. */
    val readMessages get() = car || headphones
    /** Spoken replies are cut to the first sentences. */
    val shortAnswers get() = car || headphones
    /** Tools that drive the screen are refused. */
    val noScreen get() = car
    /** Softer voice + do not disturb. */
    val quiet get() = bedtime

    fun label(): String = listOfNotNull("car".takeIf { car }, "headphones".takeIf { headphones }, "bedtime".takeIf { bedtime })
        .joinToString(", ").ifEmpty { "normal" }
}

/** The current context mode, set by the core service's watcher and read by tools and the notification listener. */
object ContextNow {
    private val state = MutableStateFlow(ContextMode())
    /** For the settings screen: shows when headphones / car turned a mode on by themselves. */
    val flow: StateFlow<ContextMode> = state.asStateFlow()
    var mode: ContextMode
        get() = state.value
        set(value) { state.value = value }
}

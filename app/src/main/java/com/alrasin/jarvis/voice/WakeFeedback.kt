package com.alrasin.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import com.alrasin.jarvis.core.JarvisLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object WakeFeedback {
    private val _startedAt = MutableStateFlow(0L)
    val startedAt: StateFlow<Long> = _startedAt

    fun signal(context: Context) {
        _startedAt.value = SystemClock.elapsedRealtime()
        runCatching {
            if (Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                context.getSystemService(Vibrator::class.java)?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build(),
                )
            }
        }.onFailure { JarvisLog.e("wake haptic unavailable", it) }
    }
}

package com.alrasin.jarvis.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import com.alrasin.jarvis.voice.ChimeSynth
import com.alrasin.jarvis.voice.WakeFeedback
import kotlin.math.PI
import kotlin.math.sin

@Composable
fun rememberWakePulse(enabled: Boolean): Float {
    val startedAt by WakeFeedback.startedAt.collectAsState()
    var pulse by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled, startedAt) {
        pulse = 0f
        if (enabled && startedAt > 0L) {
            val duration = ChimeSynth.WAKE_MS
            while (SystemClock.elapsedRealtime() - startedAt < duration) {
                withFrameMillis {
                    val fraction = ((SystemClock.elapsedRealtime() - startedAt).toFloat() / duration).coerceIn(0f, 1f)
                    pulse = sin(PI * fraction).toFloat() * 0.14f
                }
            }
            pulse = 0f
        }
    }
    return if (enabled) pulse else 0f
}

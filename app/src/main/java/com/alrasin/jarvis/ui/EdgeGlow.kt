package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import android.graphics.Matrix
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp

/** Stroke width (dp) and strength of each layer; wide faint layers under a thin bright one fake a blur (no blur on API 30). */
private val LAYERS = listOf(34f to 0.10f, 20f to 0.18f, 10f to 0.35f, 4f to 0.9f)

/**
 * Siri-like colour glow round the screen edge while the panel is open (D-057). The colours turn slowly; the glow is
 * brighter while Jimi hears you (follows the mic level), turns faster while it thinks, and stays soft when idle.
 */
@Composable
fun EdgeGlow(mood: OrbMood, level: Float, visible: Float, modifier: Modifier = Modifier) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val target = when (mood) {
        OrbMood.IDLE -> 0.35f
        OrbMood.HEARING -> 0.6f + level.coerceIn(0f, 1f) * 0.4f
        OrbMood.THINKING -> 0.85f
        OrbMood.SPEAKING -> 0.75f
    }
    val strength by animateFloatAsState(target, tween(if (motion) 180 else 0), label = "glow strength")
    val angle = if (motion) {
        val t = rememberInfiniteTransition(label = "glow")
        val a by t.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(if (mood == OrbMood.THINKING) 2200 else 6000, easing = LinearEasing)),
            label = "glow angle",
        )
        a
    } else 0f
    val colors = Hud.Glow.map { it.toArgb() }.toIntArray()
    Canvas(modifier.fillMaxSize()) {
        val cx = size.width / 2
        val cy = size.height / 2
        val brush = object : ShaderBrush() {
            override fun createShader(size: Size): Shader =
                SweepGradient(cx, cy, colors, null).apply { setLocalMatrix(Matrix().apply { setRotate(angle, cx, cy) }) }
        }
        val corner = CornerRadius(32.dp.toPx())
        LAYERS.forEach { (w, a) ->
            drawRoundRect(brush, cornerRadius = corner, style = Stroke(w.dp.toPx()), alpha = (a * strength * visible).coerceIn(0f, 1f))
        }
    }
}

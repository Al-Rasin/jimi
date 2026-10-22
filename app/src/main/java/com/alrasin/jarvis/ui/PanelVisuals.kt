package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import android.os.Build
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.alrasin.jarvis.service.JarvisCoreService
import kotlinx.coroutines.delay

object PanelColors {
    val Sheet = JimiColors.Surface2
    val Field = JimiColors.Surface3
    val Text = JimiColors.Text
    val Dim = JimiColors.SecondaryText
    val Cyan = JimiColors.Cyan
    val Blue = JimiColors.OrbBlue
    val Violet = JimiColors.Violet
    val Energy = listOf(Cyan, Blue, Violet, Cyan)
}

enum class OrbMood { IDLE, HEARING, THINKING, SPEAKING }

fun moodOf(phase: String) = when (phase) {
    JarvisCoreService.PHASE_HEARING, JarvisCoreService.PHASE_FOLLOW_UP -> OrbMood.HEARING
    JarvisCoreService.PHASE_THINKING -> OrbMood.THINKING
    JarvisCoreService.PHASE_SPEAKING -> OrbMood.SPEAKING
    else -> OrbMood.IDLE
}

private fun orbBrush(center: Offset, radius: Float) = Brush.radialGradient(
    0f to PanelColors.Cyan,
    0.55f to PanelColors.Blue,
    0.8f to PanelColors.Violet,
    1f to Color.Transparent,
    center = center,
    radius = radius,
)

@Composable
fun Orb(mood: OrbMood, level: Float, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    var scale = 1f
    var rotation = 0f
    if (motion) {
        when (mood) {
            OrbMood.IDLE, OrbMood.SPEAKING -> {
                val transition = rememberInfiniteTransition(label = "orb pulse")
                val pulse by transition.animateFloat(
                    1f, if (mood == OrbMood.IDLE) 1.04f else 1.08f,
                    infiniteRepeatable(
                        tween(if (mood == OrbMood.IDLE) 2000 else 650, easing = FastOutSlowInEasing),
                        RepeatMode.Reverse,
                    ), label = "radius",
                )
                scale = pulse
            }
            OrbMood.HEARING -> {
                val voice by animateFloatAsState(
                    1f + level.coerceIn(0f, 1f) * 0.25f,
                    spring(dampingRatio = 0.75f, stiffness = 380f), label = "mic radius",
                )
                scale = voice
            }
            OrbMood.THINKING -> {
                val transition = rememberInfiniteTransition(label = "orb thinking")
                val angle by transition.animateFloat(
                    0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sweep",
                )
                rotation = angle
            }
        }
    }
    val wakePulse = rememberWakePulse(motion && mood == OrbMood.HEARING)
    val radiusScale = maxOf(scale, 1f + wakePulse)
    val sweepAngle = rotation
    Box(modifier.size(size)) {
        val halo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Modifier.blur(48.dp, BlurredEdgeTreatment.Unbounded)
        } else Modifier
        Canvas(Modifier.matchParentSize().then(halo)) {
            val radius = this.size.minDimension * 0.32f * radiusScale * 1.6f
            drawCircle(orbBrush(center, radius), radius, alpha = 0.35f)
        }
        Canvas(Modifier.matchParentSize()) {
            val radius = this.size.minDimension * 0.32f * radiusScale
            drawCircle(orbBrush(center, radius), radius)
            if (mood == OrbMood.THINKING) {
                rotate(sweepAngle) {
                    drawCircle(
                        Brush.sweepGradient(listOf(Color.Transparent, PanelColors.Cyan, Color.Transparent), center),
                        radius * 0.8f, alpha = 0.6f, style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@Composable
fun GlowEdge(active: Boolean, modifier: Modifier = Modifier) {
    val strength by animateFloatAsState(if (active) 0.6f else 0f, tween(200), label = "listening edge")
    Canvas(modifier) {
        drawRect(
            Brush.sweepGradient(listOf(Color.Transparent, PanelColors.Cyan, Color.Transparent), center),
            topLeft = Offset(0f, (size.height - 2.dp.toPx()).coerceAtLeast(0f)),
            size = size.copy(height = 2.dp.toPx()),
            alpha = strength,
        )
    }
}

/** Reveals [text] letter by letter; if new text only adds to the end (streamed answer), keeps what is shown. */
@Composable
fun rememberTypewriter(text: String, msPerChar: Long = 14): String {
    if (!ValueAnimator.areAnimatorsEnabled()) return text
    var shown by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf("") }
    LaunchedEffect(text) {
        if (!text.startsWith(last)) shown = 0
        last = text
        while (shown < text.length) {
            delay(msPerChar)
            shown = minOf(text.length, shown + 2)
        }
    }
    return text.take(shown.coerceAtMost(text.length))
}

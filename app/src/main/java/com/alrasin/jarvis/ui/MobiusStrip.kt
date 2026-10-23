package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import kotlin.math.sin

/** Jimi's colors round the band: one blue, light → deep and back (D-067, no rainbow). */
internal val MOBIUS_COLORS = intArrayOf(
    0xFF9CC0FF.toInt(), 0xFF3A7BFF.toInt(), 0xFF1E4FD8.toInt(), 0xFF2A5FF0.toInt(), 0xFF1E4FD8.toInt(), 0xFF3A7BFF.toInt(),
)

/**
 * Jimi's living Möbius strip (D-050). It draws itself on when it first appears, then shows the state:
 * idle = slow turn, listening = ripples that follow the voice, thinking = fast turn with flowing color,
 * speaking = a wave that pulses round the band. Changes between states blend smoothly. [still] = no motion (small icons).
 */
@Composable
fun MobiusStrip(
    mood: OrbMood, level: Float = 0f, size: Dp = 64.dp, modifier: Modifier = Modifier, intro: Boolean = true, still: Boolean = false,
) {
    val motion = ValueAnimator.areAnimatorsEnabled() && !still
    val shown = remember { Animatable(if (intro && motion) 0f else 1f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }

    val soft = tween<Float>(500)
    val spinSpeed by animateFloatAsState(
        when (mood) { OrbMood.IDLE -> 0.45f; OrbMood.HEARING -> 0.9f; OrbMood.THINKING -> 3.4f; OrbMood.SPEAKING -> 1.3f }, soft, label = "spin",
    )
    val flowSpeed by animateFloatAsState(if (mood == OrbMood.THINKING) 0.5f else 0.05f, soft, label = "flow")
    val calm by animateFloatAsState(if (mood == OrbMood.IDLE) 0.025f else 0.01f, soft, label = "calm")
    val voice by animateFloatAsState(
        if (mood == OrbMood.HEARING) 0.03f + level.coerceIn(0f, 1f) * 0.14f else 0f,
        spring(dampingRatio = 0.7f, stiffness = 300f), label = "voice",
    )
    val talk by animateFloatAsState(if (mood == OrbMood.SPEAKING) 0.07f else 0f, soft, label = "talk")
    val glow by animateFloatAsState(
        when (mood) { OrbMood.IDLE -> 0.22f; OrbMood.HEARING -> 0.4f + level.coerceIn(0f, 1f) * 0.4f; else -> 0.45f }, soft, label = "glow",
    )

    // time keeps running so a speed change never makes the band jump
    var spin by remember { mutableFloatStateOf(0.6f) }
    var flow by remember { mutableFloatStateOf(0f) }
    var clock by remember { mutableFloatStateOf(0f) }
    val speeds by rememberUpdatedState(spinSpeed to flowSpeed)
    if (motion) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                withFrameNanos { now ->
                    if (last != 0L) {
                        val dt = ((now - last) / 1e9f).coerceAtMost(0.05f)
                        spin += dt * speeds.first
                        flow += dt * speeds.second
                        clock += dt
                    }
                    last = now
                }
            }
        }
    }

    val path = remember { Path() }
    Canvas(modifier.size(size).semantics { contentDescription = "Jimi" }) {
        val dim = this.size.minDimension
        val c = center
        val pulse = 0.6f + 0.4f * sin(clock * 7f)
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF6A8DFF).copy(alpha = glow * (if (talk > 0f) pulse else 1f)), Color.Transparent), c, dim / 2),
            dim / 2, c,
        )
        val s = shown.value
        val pose = MobiusMath.Pose(
            spin = spin + (1f - s) * 2.4f,
            sway = 0.18f * sin(clock * 0.6f),
            waves = listOf(
                Triple(calm, 2, clock * 0.8f),
                Triple(voice, 6, clock * 6f),
                Triple(talk * pulse, 3, clock * 5f),
            ),
        )
        val unit = dim / 2 / 1.75f * (0.6f + 0.4f * s)
        val edge = 1.dp.toPx().coerceAtMost(dim / 80f)
        for (f in MobiusMath.faces(pose, shown = s)) {
            val base = MobiusMath.blend(MOBIUS_COLORS, f.along + flow)
            val near = ((f.depth + 0.6f) / 1.2f).coerceIn(0f, 1f)
            val color = shade(base, f.light, 0.55f + 0.45f * near)
            path.reset()
            path.moveTo(c.x + f.xs[0] * unit, c.y + f.ys[0] * unit)
            for (k in 1..3) path.lineTo(c.x + f.xs[k] * unit, c.y + f.ys[k] * unit)
            path.close()
            drawPath(path, color)
            // bright rims make the band read as a thin ribbon
            val rim = color.copy(alpha = color.alpha * 0.9f).lighten(0.35f)
            drawLine(rim, Offset(c.x + f.xs[1] * unit, c.y + f.ys[1] * unit), Offset(c.x + f.xs[2] * unit, c.y + f.ys[2] * unit), edge)
            drawLine(rim, Offset(c.x + f.xs[0] * unit, c.y + f.ys[0] * unit), Offset(c.x + f.xs[3] * unit, c.y + f.ys[3] * unit), edge)
        }
    }
}

internal fun shade(argb: Int, light: Float, alpha: Float): Color {
    val r = ((argb shr 16) and 0xFF) / 255f * light
    val g = ((argb shr 8) and 0xFF) / 255f * light
    val b = (argb and 0xFF) / 255f * light
    return Color(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), alpha)
}

internal fun Color.lighten(t: Float) = Color(red + (1 - red) * t, green + (1 - green) * t, blue + (1 - blue) * t, alpha)

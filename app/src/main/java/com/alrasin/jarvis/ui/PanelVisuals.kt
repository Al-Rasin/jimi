package com.alrasin.jarvis.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/** Look of the assistant panel: one dark surface, a cyan → violet → pink "energy" gradient. */
object PanelColors {
    val Sheet = Color(0xF20B0E16)
    val Field = Color(0xFF161B26)
    val Text = Color(0xFFE8ECF4)
    val Dim = Color(0xFF8A93A6)
    val Cyan = Color(0xFF4FD8FF)
    val Blue = Color(0xFF5B7CFF)
    val Violet = Color(0xFFA35BFF)
    val Pink = Color(0xFFFF5BC8)
    val Energy = listOf(Cyan, Blue, Violet, Pink, Cyan)
}

enum class OrbMood { IDLE, HEARING, THINKING, SPEAKING }

/**
 * Living orb: a spinning colour core with liquid blobs inside. It breathes when idle, follows the
 * voice loudness while hearing, spins fast while thinking and pulses while speaking.
 */
@Composable
fun Orb(mood: OrbMood, level: Float, size: Dp = 64.dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "orb")
    val slow by t.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "slow")
    val fast by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "fast")
    val breathe by t.animateFloat(
        0.92f, 1.04f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe",
    )
    val pulse by t.animateFloat(
        0.94f, 1.08f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val voice by animateFloatAsState(level, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "voice")
    val spin = if (mood == OrbMood.THINKING) fast else slow
    val scale = when (mood) {
        OrbMood.HEARING -> 0.9f + voice * 0.3f
        OrbMood.SPEAKING -> pulse
        OrbMood.THINKING -> 0.96f
        OrbMood.IDLE -> breathe
    }
    val glow = when (mood) {
        OrbMood.IDLE -> 0.35f
        OrbMood.HEARING -> 0.45f + voice * 0.4f
        else -> 0.6f
    }

    Canvas(modifier.size(size)) {
        val c = center
        val r = this.size.minDimension / 2f * 0.62f * scale
        drawCircle(
            Brush.radialGradient(listOf(PanelColors.Violet.copy(alpha = glow), Color.Transparent), c, r * 1.6f),
            r * 1.6f, c,
        )
        val ball = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
        clipPath(ball) {
            rotate(spin, c) { drawCircle(Brush.sweepGradient(PanelColors.Energy, c), r, c) }
            // three blobs orbiting at different speeds = "liquid" look
            listOf(PanelColors.Cyan to 1f, PanelColors.Pink to -1.6f, PanelColors.Blue to 2.3f).forEachIndexed { i, (col, k) ->
                val a = Math.toRadians((spin * k + i * 120).toDouble())
                val p = Offset(c.x + (r * 0.45f) * cos(a).toFloat(), c.y + (r * 0.45f) * sin(a).toFloat())
                drawCircle(Brush.radialGradient(listOf(col.copy(alpha = 0.85f), Color.Transparent), p, r * 0.7f), r * 0.7f, p)
            }
            val shine = Offset(c.x - r * 0.35f, c.y - r * 0.4f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), shine, r * 0.6f), r * 0.6f, shine)
        }
    }
}

/** Moving gradient line + soft glow on the top edge of the sheet. [active] makes it brighter. */
@Composable
fun GlowEdge(active: Boolean, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "edge")
    val shift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "shift")
    val strength by animateFloatAsState(if (active) 1f else 0.45f, tween(500), label = "strength")
    Canvas(modifier) {
        val w = size.width
        val x = shift * w
        val line = Brush.linearGradient(PanelColors.Energy, Offset(x - w, 0f), Offset(x + w, 0f), androidx.compose.ui.graphics.TileMode.Mirror)
        drawRect(
            Brush.verticalGradient(listOf(Color.Transparent, PanelColors.Violet.copy(alpha = 0.28f * strength))),
            size = size.copy(height = size.height - 3.dp.toPx()),
        )
        drawRect(line, topLeft = Offset(0f, size.height - 3.dp.toPx()), size = size.copy(height = 3.dp.toPx()), alpha = strength)
    }
}

/** Reveals [text] letter by letter; if new text only adds to the end (streamed answer), keeps what is shown. */
@Composable
fun rememberTypewriter(text: String, msPerChar: Long = 14): String {
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

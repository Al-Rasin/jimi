package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Light frosted glass for the assistant panel (D-057): white tint over the blurred screen, hairline edge, ink text. */
object Hud {
    val Tint = Color.White
    val Glass = Color.Black.copy(alpha = 0.04f)
    val Hairline = Color.Black.copy(alpha = 0.08f)
    val accentBrush = Brush.linearGradient(listOf(PanelColors.Cyan, PanelColors.Violet))
    /** Siri-like edge colours: cyan, blue, violet, pink. */
    val Glow = listOf(Color(0xFF3DD5F3), Color(0xFF5B7CFA), Color(0xFF9B6BFF), Color(0xFFFF6FB5), Color(0xFF3DD5F3))
}

/** The orb inside a thin ring; a short arc travels around it while Jimi is busy. */
@Composable
fun HudOrb(mood: OrbMood, level: Float, size: Dp = 84.dp) {
    val motion = ValueAnimator.areAnimatorsEnabled()
    val busy = mood != OrbMood.IDLE
    val arcAlpha by animateFloatAsState(if (busy) 1f else 0f, tween(250), label = "arc")
    val angle = if (motion && busy) {
        val t = rememberInfiniteTransition(label = "hud ring")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (mood == OrbMood.THINKING) 1100 else 2600, easing = LinearEasing)), label = "arc angle")
        a
    } else 0f
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = 1.5.dp.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawCircle(Color.Black.copy(alpha = 0.06f), radius = this.size.minDimension / 2 - inset, style = Stroke(w))
            if (arcAlpha > 0f) {
                drawArc(
                    Brush.sweepGradient(listOf(Color.Transparent, PanelColors.Cyan, PanelColors.Violet), center),
                    startAngle = angle, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(inset, inset), size = arcSize, alpha = arcAlpha,
                    style = Stroke(w * 1.4f, cap = StrokeCap.Round),
                )
            }
        }
        Orb(mood, level, size = size * 0.78f)
    }
}

/** Small spaced capitals, e.g. "LISTENING". */
@Composable
fun HudLabel(text: String, active: Boolean) {
    // long status messages read better as plain text than spaced capitals
    val short = text.length <= 18
    Text(
        if (short) text.uppercase() else text,
        style = if (short) MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp, fontWeight = FontWeight.Medium)
        else MaterialTheme.typography.bodySmall,
        color = if (active) PanelColors.Cyan else PanelColors.Dim,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 48.dp),
    )
}

/** User line: right, soft. Jimi line: left, ink, with a thin light bar. No heavy bubbles. */
@Composable
fun HudLine(text: String, mine: Boolean) {
    if (text.isBlank()) return
    if (mine) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = PanelColors.Dim,
                modifier = Modifier.widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Hud.Glass)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    } else {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(Hud.accentBrush, RoundedCornerShape(1.dp)))
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
                color = PanelColors.Text,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
}

@Composable
/** [selected] = the user's choice (cyan); a chip that is only locked is dimmed, so it never looks chosen. */
fun HudChip(text: String, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = when {
            selected -> PanelColors.Cyan
            enabled -> PanelColors.Text
            else -> PanelColors.Text.copy(alpha = 0.35f)
        },
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(CircleShape)
            .border(1.dp, Hud.Hairline, CircleShape)
            .background(Hud.Glass)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

/** Glass text field; glowing mic button (send arrow when text is typed). */
@Composable
fun HudInput(text: String, onText: (String) -> Unit, onGo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Hud.Glass).border(1.dp, Hud.Hairline, CircleShape)
            .padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text, onValueChange = onText, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = PanelColors.Text),
            cursorBrush = SolidColor(PanelColors.Cyan),
            modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Ask Jimi…", color = PanelColors.Dim)
                inner()
            },
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Hud.accentBrush).clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isBlank()) MicGlyph(Color.White)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun MicGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.minDimension
        val w = s * 0.1f
        drawRoundRect(color, Offset(s * 0.35f, s * 0.05f), Size(s * 0.3f, s * 0.52f), CornerRadius(s * 0.15f))
        drawArc(color, 0f, 180f, false, Offset(s * 0.2f, s * 0.22f), Size(s * 0.6f, s * 0.5f), style = Stroke(w, cap = StrokeCap.Round))
        drawLine(color, Offset(s / 2, s * 0.72f), Offset(s / 2, s * 0.92f), w, StrokeCap.Round)
    }
}

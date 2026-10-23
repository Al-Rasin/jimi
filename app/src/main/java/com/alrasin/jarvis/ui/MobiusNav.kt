package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Bottom bar as a twisting Möbius ribbon (D-050). The ribbon makes a half twist per tab; when pages swipe or the
 * bar is dragged, the ribbon rolls with the finger and a glow slides to the tab. Dragging the bar moves the pages too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobiusNav(items: List<Pair<String, ImageVector>>, pager: PagerState, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val position by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }
    val keyboardOpen = WindowInsets.isImeVisible
    val motion = ValueAnimator.areAnimatorsEnabled()

    fun go(index: Int) = scope.launch {
        val i = index.coerceIn(items.indices)
        if (motion) pager.animateScrollToPage(i) else pager.scrollToPage(i)
    }

    // while the bar is dragged, the pages sit exactly where the finger says
    var dragTo by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { dragTo }.filterNotNull().collectLatest { p ->
            val page = p.roundToInt()
            pager.scrollToPage(page, (p - page).coerceIn(-0.5f, 0.5f))
        }
    }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { position.roundToInt() }.distinctUntilChanged().collect {
            if (!first) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            first = false
        }
    }

    AnimatedVisibility(
        visible = !keyboardOpen,
        enter = slideInVertically(tween(200)) { it },
        exit = slideOutVertically(tween(200)) { it },
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
            val shape = RoundedCornerShape(28.dp)
            val velocity = remember { VelocityTracker() }
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(76.dp).clip(shape)
                    .background(JimiColors.Surface1.copy(alpha = 0.85f))
                    .border(1.dp, Hud.edgeBrush, shape)
                    // on the parent: the tab row on top would get the touch first and the drag would never start
                    .pointerInput(items.size) {
                        val slot = size.width.toFloat() / items.size
                        var start = 0f
                        var moved = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { velocity.resetTracking(); start = position; moved = 0f },
                            onDragEnd = {
                                // the page may lag a frame behind the finger, so end where the finger is
                                val end = dragTo ?: position
                                dragTo = null
                                val v = velocity.calculateVelocity().x
                                // a flick moves one more tab the way the finger went
                                go((end + (if (abs(v) > 800f) v / abs(v) * 0.5f else 0f)).roundToInt())
                            },
                            onDragCancel = { dragTo = null; go(position.roundToInt()) },
                        ) { change, dx ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            // ribbon follows the finger: one slot of drag = one page
                            moved += dx
                            dragTo = (start + moved / slot).coerceIn(0f, items.lastIndex.toFloat())
                            change.consume()
                        }
                    }
                    .selectableGroup(),
            ) {
                val slotPx = constraints.maxWidth.toFloat() / items.size
                val path = remember { Path() }
                Canvas(Modifier.fillMaxSize()) {
                    val mid = size.height * 0.38f
                    val half = size.height * 0.16f
                    val steps = 96
                    val glowX = (position + 0.5f) * slotPx
                    for (i in 0 until steps) {
                        val x0 = size.width * i / steps
                        val x1 = size.width * (i + 1) / steps
                        // half twist per slot; the twist pattern rolls with the position
                        fun twist(x: Float) = cos(PI.toFloat() * (x / slotPx - 0.5f - position))
                        val t0 = twist(x0); val t1 = twist(x1)
                        val front = (t0 + t1) >= 0f
                        val xm = (x0 + x1) / 2
                        val near = exp(-((xm - glowX) / (slotPx * 0.55f)).let { it * it })
                        val base = MobiusMath.blend(NAV_COLORS, xm / size.width * 0.5f + position * 0.08f)
                        val color = Color(base).copy(alpha = (if (front) 0.32f else 0.16f) + 0.55f * near)
                        path.reset()
                        path.moveTo(x0, mid - half * t0); path.lineTo(x1, mid - half * t1)
                        path.lineTo(x1, mid + half * t1); path.lineTo(x0, mid + half * t0); path.close()
                        drawPath(path, color)
                        drawLine(color.copy(alpha = (color.alpha + 0.2f).coerceAtMost(1f)), Offset(x0, mid - half * t0), Offset(x1, mid - half * t1), 1.dp.toPx())
                    }
                    drawCircle(
                        Brush.radialGradient(listOf(JimiColors.Cyan.copy(alpha = 0.35f), Color.Transparent), Offset(glowX, mid), slotPx * 0.6f),
                        slotPx * 0.6f, Offset(glowX, mid),
                    )
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    items.forEachIndexed { index, (label, icon) ->
                        val near = (1f - abs(position - index)).coerceIn(0f, 1f)
                        val active = position.roundToInt() == index
                        Column(
                            Modifier.weight(1f).fillMaxHeight()
                                .selectable(selected = active, role = Role.Tab, onClick = { go(index) })
                                .semantics { contentDescription = label },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Icon(
                                icon, contentDescription = null,
                                tint = lerpColor(JimiColors.TertiaryText, JimiColors.Text, near),
                                modifier = Modifier.size(22.dp).graphicsLayer { scaleX = 0.9f + 0.25f * near; scaleY = scaleX },
                            )
                            Text(
                                label, style = MaterialTheme.typography.labelSmall,
                                color = JimiColors.Cyan.copy(alpha = near),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val NAV_COLORS = intArrayOf(0xFF5BD8E6.toInt(), 0xFF6A8DFF.toInt(), 0xFFA35BFF.toInt(), 0xFF6A8DFF.toInt())

private fun lerpColor(a: Color, b: Color, t: Float) =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

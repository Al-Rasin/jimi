package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ArcDial(
    items: List<Pair<String, ImageVector>>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val selection = selectedIndex.coerceIn(items.indices)
    val step = ArcDialMath.step(items.size)
    val rotation = remember(items.size) { Animatable(selection * step) }
    var dragging by remember { mutableStateOf(false) }
    var dragAngle by remember { mutableFloatStateOf(selection * step) }
    var motion by remember { mutableStateOf<Job?>(null) }
    val angle = if (dragging) dragAngle else rotation.value
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val currentSelection by rememberUpdatedState(selection)
    val select by rememberUpdatedState(onSelected)
    val currentAngle by rememberUpdatedState(angle)
    val keyboardOpen = WindowInsets.isImeVisible

    fun settle(index: Int) {
        motion?.cancel()
        motion = scope.launch {
            rotation.snapTo(if (dragging) dragAngle else rotation.value)
            dragging = false
            if (ValueAnimator.areAnimatorsEnabled()) {
                rotation.animateTo(index * step, spring(dampingRatio = 0.75f, stiffness = 380f))
            } else {
                rotation.snapTo(index * step)
            }
            if (index != currentSelection) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                select(index)
            }
        }
    }

    LaunchedEffect(selection) {
        if (!dragging && motion?.isActive != true) rotation.snapTo(selection * step)
    }
    LaunchedEffect(items.size) {
        var first = true
        snapshotFlow {
            val position = (if (dragging) dragAngle else rotation.value).coerceIn(0f, items.lastIndex * step)
            (position / step).toInt()
        }
            .distinctUntilChanged().collect {
                if (!first) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                first = false
            }
    }
    LaunchedEffect(keyboardOpen) {
        if (keyboardOpen) {
            motion?.cancel()
            dragging = false
            rotation.snapTo(currentSelection * step)
        }
    }

    AnimatedVisibility(
        visible = !keyboardOpen,
        enter = slideInVertically(tween(200)) { it },
        exit = slideOutVertically(tween(200)) { it },
        modifier = modifier,
    ) {
        Box(Modifier.fillMaxWidth().navigationBarsPadding()) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().height(144.dp).clipToBounds().selectableGroup()
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Previous screen") {
                                if (currentSelection > 0) { settle(currentSelection - 1); true } else false
                            },
                            CustomAccessibilityAction("Next screen") {
                                if (currentSelection < items.lastIndex) { settle(currentSelection + 1); true } else false
                            },
                        )
                    },
            ) {
                val density = LocalDensity.current
                val radius = minOf(150.dp, (maxWidth - 64.dp) / 2)
                val radiusPx = with(density) { radius.toPx() }
                val outerPx = with(density) { (radius + 50.dp).toPx() }
                val center = Offset(with(density) { maxWidth.toPx() } / 2, outerPx + with(density) { 32.dp.toPx() })
                val chipPx = with(density) { 56.dp.toPx() }
                val velocity = remember { VelocityTracker() }
                Box(
                    Modifier.fillMaxSize().pointerInput(items.size, center) {
                        var previous = 0f
                        var rawAngle = 0f
                        detectDragGestures(
                            onDragStart = { position ->
                                motion?.cancel()
                                rawAngle = currentAngle
                                dragAngle = rawAngle
                                dragging = true
                                previous = ArcDialMath.pointerAngle(position.x, position.y, center.x, center.y)
                                velocity.resetTracking()
                            },
                            onDragEnd = {
                                settle(ArcDialMath.snapIndex(dragAngle, velocity.calculateVelocity().x, items.size))
                            },
                            onDragCancel = { settle(ArcDialMath.snapIndex(dragAngle, 0f, items.size)) },
                        ) { change, _ ->
                            val next = ArcDialMath.pointerAngle(change.position.x, change.position.y, center.x, center.y)
                            rawAngle -= ArcDialMath.delta(previous, next)
                            previous = next
                            dragAngle = ArcDialMath.rubberBand(rawAngle, items.size)
                            velocity.addPosition(change.uptimeMillis, Offset(rawAngle, 0f))
                            change.consume()
                        }
                    },
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(JimiColors.Surface2, outerPx, center)
                        drawCircle(JimiColors.Outline, outerPx, center, style = Stroke(1.dp.toPx()))
                        drawCircle(
                            Brush.radialGradient(
                                0f to Color.Transparent,
                                ((outerPx - 24.dp.toPx()) / outerPx) to Color.Transparent,
                                1f to JimiColors.Background.copy(alpha = 0.7f),
                                center = center, radius = outerPx,
                            ), outerPx, center,
                        )
                        val dot = Offset(center.x, center.y - outerPx - 3.dp.toPx())
                        drawCircle(Brush.radialGradient(listOf(JimiColors.Cyan.copy(alpha = 0.4f), Color.Transparent), dot, 9.dp.toPx()), 9.dp.toPx(), dot)
                        drawCircle(JimiColors.Cyan, 3.dp.toPx(), dot)
                    }
                    items.forEachIndexed { index, (label, icon) ->
                        val degrees = index * step - angle
                        val distance = abs(degrees)
                        if (distance < 72f) {
                            val radians = Math.toRadians(degrees.toDouble())
                            val active = index == selection
                            val fade = ((72f - distance) / 36f).coerceIn(0f, 1f)
                            Box(
                                Modifier.offset {
                                    IntOffset(
                                        (center.x + radiusPx * sin(radians) - chipPx / 2).roundToInt(),
                                        (center.y - radiusPx * cos(radians) - chipPx / 2).roundToInt(),
                                    )
                                }.size(56.dp).graphicsLayer { alpha = fade }
                                    .clip(CircleShape)
                                    .background(if (active) JimiColors.Cyan.copy(alpha = 0.14f) else Color.Transparent)
                                    .selectable(selected = active, role = Role.Tab, onClick = { settle(index) })
                                    .semantics { contentDescription = label },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    icon, contentDescription = null,
                                    tint = if (active) JimiColors.Cyan else JimiColors.TertiaryText.copy(alpha = 0.6f),
                                    modifier = Modifier.size(if (active) 28.dp else 22.dp).graphicsLayer {
                                        scaleX = if (active) 1f else 0.7f
                                        scaleY = scaleX
                                    },
                                )
                            }
                        }
                    }
                }
                AnimatedContent(
                    targetState = items[selection].first,
                    transitionSpec = {
                        (fadeIn(tween(150)) + slideInVertically(tween(150)) { with(density) { 4.dp.roundToPx() } })
                            .togetherWith(fadeOut(tween(150)) + slideOutVertically(tween(150)) { -with(density) { 4.dp.roundToPx() } })
                    },
                    modifier = Modifier.align(Alignment.TopCenter), label = "Dial label",
                ) { label ->
                    Text(label, style = MaterialTheme.typography.labelLarge, color = JimiColors.Text)
                }
            }
        }
    }
}

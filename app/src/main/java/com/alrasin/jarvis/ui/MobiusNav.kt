package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Bottom bar as a Möbius loop (D-050, D-051). The tabs sit round the loop; the one at the front (bottom middle) is open.
 * Drag the loop sideways and it turns, bringing the next tab to the front; tap a tab to turn it to the front.
 * It is a loop, so after the last tab comes the first. Swiping pages turns the loop too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MobiusNav(items: List<Pair<String, ImageVector>>, pager: PagerState, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val n = items.size
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val keyboardOpen = WindowInsets.isImeVisible
    val motion = ValueAnimator.areAnimatorsEnabled()
    val painters = items.map { rememberVectorPainter(it.second) }
    val pagePos by remember { derivedStateOf { pager.currentPage + pager.currentPageOffsetFraction } }

    // how far the loop has turned, in tabs; not limited to 0..n-1 so it can keep going round
    val turn = remember { Animatable(pagePos) }
    var busy by remember { mutableStateOf(false) }
    fun wrap(x: Int) = ((x % n) + n) % n
    val front by remember { derivedStateOf { wrap(turn.value.roundToInt()) } }

    // pages swiped by hand: turn the loop the short way to match
    LaunchedEffect(Unit) {
        snapshotFlow { pagePos }.collect { p ->
            if (!busy) turn.snapTo(p + n * ((turn.value - p) / n).roundToInt())
        }
    }
    LaunchedEffect(Unit) {
        var first = true
        snapshotFlow { front }.distinctUntilChanged().collect {
            if (!first) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            first = false
        }
    }

    // settle on a whole tab; a jump over the loop's seam (last ↔ first) changes the page at once
    fun settle(target: Int) = scope.launch {
        busy = true
        val page = wrap(target)
        launch {
            if (motion && abs(page - pager.currentPage) <= 1) pager.animateScrollToPage(page) else pager.scrollToPage(page)
        }
        if (motion) turn.animateTo(target.toFloat(), spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
        else turn.snapTo(target.toFloat())
        busy = false
    }

    AnimatedVisibility(
        visible = !keyboardOpen,
        enter = slideInVertically(tween(200)) { it },
        exit = slideOutVertically(tween(200)) { it },
        modifier = modifier,
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, JimiColors.Background.copy(alpha = 0.95f))))
                .navigationBarsPadding()
                .height(148.dp)
                .clearAndSetSemantics {
                    contentDescription = "Tabs, ${items[front].first} open"
                    customActions = items.mapIndexed { i, (label, _) ->
                        CustomAccessibilityAction("Open $label") { settle(turn.value.roundToInt() + shortWay(front, i, n)); true }
                    }
                },
        ) {
            val w = constraints.maxWidth.toFloat()
            val h = constraints.maxHeight.toFloat()
            // the loop takes ~70 % of the width (not edge to edge) and more height, so it reads as a ring
            val sx = w * 0.35f / 1.32f
            val sy = (h - with(density) { 52.dp.toPx() }) / 1.2f
            val cy = h / 2 - with(density) { 6.dp.toPx() }
            val pose by remember { derivedStateOf { MobiusMath.Pose(spin = MobiusMath.tabSpin(turn.value, n), tilt = 1.2f, halfWidth = 0.13f) } }
            // one tab of drag = the length of loop between two tabs at the front
            val slotPx = sx * 2 * PI.toFloat() / n
            val spots = remember(n) { Array(n) { FloatArray(3) } }
            val velocity = remember { VelocityTracker() }
            val path = remember { Path() }
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(n, slotPx) {
                        var start = 0f
                        var moved = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { velocity.resetTracking(); busy = true; start = turn.value; moved = 0f },
                            onDragEnd = {
                                val v = velocity.calculateVelocity().x
                                // a flick turns one more tab the way the finger went
                                val flick = if (abs(v) > 800f) -v / abs(v) * 0.5f else 0f
                                settle((turn.value + flick).roundToInt())
                            },
                            onDragCancel = { settle(turn.value.roundToInt()) },
                        ) { change, dx ->
                            velocity.addPosition(change.uptimeMillis, change.position)
                            moved += dx
                            val t = start - moved / slotPx
                            scope.launch {
                                turn.snapTo(t)
                                val near = t.roundToInt()
                                pager.scrollToPage(wrap(near), (t - near).coerceIn(-0.5f, 0.5f))
                            }
                            change.consume()
                        }
                    }
                    .pointerInput(n) {
                        detectTapGestures { at ->
                            // nearest tab under the finger, front ones first
                            val hit = (0 until n)
                                .filter { hypot(spots[it][0] - at.x, spots[it][1] - at.y) < 34.dp.toPx() }
                                .maxByOrNull { spots[it][2] } ?: return@detectTapGestures
                            settle(turn.value.roundToInt() + shortWay(front, hit, n))
                        }
                    },
            ) {
                val c = Offset(w / 2, cy)
                val faces = MobiusMath.faces(pose, segments = 120)
                val tabs = (0 until n).map { i ->
                    val p = MobiusMath.project(MobiusMath.point(MobiusMath.tabU(i, n), 0f, pose.halfWidth), pose)
                    spots[i][0] = c.x + p[0] * sx; spots[i][1] = c.y + p[1] * sy; spots[i][2] = p[2]
                    i to p[2] + 0.04f
                }
                val glowR = 30.dp.toPx()
                fun drawTab(i: Int) {
                    val x = spots[i][0]; val y = spots[i][1]
                    val near = ((spots[i][2] + 1f) / 2f).coerceIn(0f, 1f)
                    val focus = (1f - abs(wrapDist(turn.value, i.toFloat(), n))).coerceIn(0f, 1f)
                    if (focus > 0f) {
                        drawCircle(
                            Brush.radialGradient(listOf(JimiColors.Accent.copy(alpha = 0.22f * focus), Color.Transparent), Offset(x, y), glowR),
                            glowR, Offset(x, y),
                        )
                    }
                    // near-even sizes so the set reads as one row of icons, not random blobs
                    val s = (18 + 5 * near + 3 * focus).dp.toPx()
                    val tint = lerp(JimiColors.SecondaryText, JimiColors.Accent, focus)
                    translate(x - s / 2, y - s / 2) {
                        with(painters[i]) { draw(Size(s, s), alpha = 0.3f + 0.7f * near, colorFilter = ColorFilter.tint(tint)) }
                    }
                }
                // band pieces and tab icons painted far to near, so the loop hides tabs behind it
                val order = tabs.sortedBy { it.second }
                var t = 0
                for (f in faces) {
                    while (t < order.size && order[t].second <= f.depth) drawTab(order[t++].first)
                    val base = MobiusMath.blend(MOBIUS_COLORS, f.along)
                    val depthNear = ((f.depth + 0.6f) / 1.2f).coerceIn(0f, 1f)
                    val color = shade(base, f.light, 0.16f + 0.34f * depthNear)
                    path.reset()
                    path.moveTo(c.x + f.xs[0] * sx, c.y + f.ys[0] * sy)
                    for (k in 1..3) path.lineTo(c.x + f.xs[k] * sx, c.y + f.ys[k] * sy)
                    path.close()
                    drawPath(path, color)
                    val rim = color.copy(alpha = (color.alpha + 0.25f).coerceAtMost(1f)).lighten(0.3f)
                    drawLine(rim, Offset(c.x + f.xs[1] * sx, c.y + f.ys[1] * sy), Offset(c.x + f.xs[2] * sx, c.y + f.ys[2] * sy), 1.dp.toPx())
                    drawLine(rim, Offset(c.x + f.xs[0] * sx, c.y + f.ys[0] * sy), Offset(c.x + f.xs[3] * sx, c.y + f.ys[3] * sy), 1.dp.toPx())
                }
                while (t < order.size) drawTab(order[t++].first)
            }
            // the open tab's name sits in the hole of the loop
            Crossfade(
                items[front].first,
                Modifier.align(Alignment.TopCenter).padding(top = with(density) { (cy - 9.dp.toPx()).toDp() }),
                label = "tab name",
            ) {
                Text(it, style = MaterialTheme.typography.labelMedium, color = JimiColors.Accent)
            }
        }
    }
}

/** Steps from tab [from] to tab [to] the short way round a loop of [n] (negative = backwards). */
internal fun shortWay(from: Int, to: Int, n: Int): Int {
    val d = ((to - from) % n + n) % n
    return if (d > n / 2) d - n else d
}

/** Distance from turn [a] to tab [b] round a loop of [n], in tabs (-n/2..n/2). */
private fun wrapDist(a: Float, b: Float, n: Int): Float {
    val d = (a - b) / n
    return (d - floor(d + 0.5f)) * n
}

private fun lerp(a: Color, b: Color, t: Float) =
    Color(a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t)

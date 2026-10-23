package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.background
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.service.JarvisCoreService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * The assistant panel (replaces the Google Assistant / Gemini sheet, D-036): a floating glass card that shows a blurred
 * picture of the screen behind it, with Jimi's living Möbius strip (D-050, D-051). Opens on the wake word, also on the lock screen.
 * Shows live text and the answer, and lets the user fix wrong text without a pop-up. Closes itself
 * [IDLE_MS] after the conversation ends and nothing was touched.
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {
    private val vm: PanelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        current = WeakReference(this)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            JimiTheme {
                Panel(vm, onClose = ::finish)
            }
        }
    }

    override fun onDestroy() {
        if (current?.get() === this) {
            current = null
            Backdrop.clear()
        }
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<AssistantActivity>? = null
        fun hideForScreenAgent() { current?.get()?.finish() }
        val isOpen get() = current?.get() != null
        const val NOTHING_HEARD = "(nothing heard)"
        const val IDLE_MS = 30_000L
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Panel(vm: PanelViewModel, onClose: () -> Unit) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val reply by vm.reply.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    val past by vm.exchanges.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val reported by vm.reported.collectAsStateWithLifecycle()
    val backdrop by Backdrop.image.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf(false) }
    var fixText by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var rated by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val idle = phase == JarvisCoreService.PHASE_WAKE
    val mood = moodOf(phase)
    val motion = ValueAnimator.areAnimatorsEnabled()
    val replyShown = rememberTypewriter(reply)
    val talking = past.isNotEmpty() || live
    val fade by animateFloatAsState(if (shown) 1f else 0f, tween(if (motion) 250 else 0), label = "panel fade")
    val stripSize by animateDpAsState(if (talking) 72.dp else 140.dp, tween(if (motion) 450 else 0), label = "strip size")

    fun close() { shown = false; closing = true }
    fun touch() { touched = System.currentTimeMillis() }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(closing) {
        if (closing) {
            delay(if (motion) 250 else 0)
            onClose()
        }
    }
    LaunchedEffect(heard) { rated = false }
    LaunchedEffect(idle, touched) {
        if (idle) {
            delay(AssistantActivity.IDLE_MS)
            close()
        }
    }

    // floating glass card (D-050, D-051): the app stays visible around it; a tap outside closes
    val density = LocalDensity.current
    var screen by remember { mutableStateOf(IntSize.Zero) }
    var cardAt by remember { mutableStateOf(Offset.Zero) }
    val image = remember(backdrop) { backdrop?.asImageBitmap() }
    val shape = RoundedCornerShape(32.dp)
    Box(
        Modifier.fillMaxSize().onSizeChanged { screen = it }
            .background(Color.Black.copy(alpha = 0.12f * fade))
            .clickable(remember { MutableInteractionSource() }, null, onClick = ::close)
            .navigationBarsPadding().imePadding().padding(12.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = with(density) { (screen.height * 0.72f).toDp() })
                .graphicsLayer { alpha = fade; translationY = (1f - fade) * 48.dp.toPx() }
                .onGloballyPositioned { cardAt = it.positionInWindow() }
                .clip(shape)
                // the blurred screen, placed so the card shows exactly what is behind it
                .drawBehind {
                    image?.let {
                        drawImage(
                            it, dstOffset = IntOffset(-cardAt.x.roundToInt(), -cardAt.y.roundToInt()),
                            dstSize = screen, filterQuality = FilterQuality.High,
                        )
                    }
                }
                .background(Hud.SheetBottom.copy(alpha = if (image != null) 0.55f else 0.92f))
                .border(1.dp, Hud.edgeBrush, shape)
                .clickable(remember { MutableInteractionSource() }, null) { touch() }
                .padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 14.dp)
                .animateContentSize(tween(if (motion) 200 else 0)),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.fillMaxWidth()) {
                Row(Modifier.align(Alignment.TopEnd)) {
                    if (phase == JarvisCoreService.PHASE_SPEAKING) {
                        TextButton(onClick = { touch(); vm.shutUp() }) { Text("STOP", color = PanelColors.Accent, letterSpacing = 2.sp) }
                    }
                    TextButton(onClick = ::close) { Text("✕", color = PanelColors.Dim, fontSize = 18.sp) }
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MobiusStrip(mood, level, size = stripSize, modifier = Modifier.clickable(remember { MutableInteractionSource() }, null) { touch(); vm.listen() })
                    AnimatedContent(if (idle) "Jimi" else phase, label = "phase") { p -> HudLabel(p, active = !idle) }
                    if (!talking && heard != AssistantActivity.NOTHING_HEARD) {
                        Text(
                            "How can I help, Boss?",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
                            color = PanelColors.Text, textAlign = TextAlign.Center,
                        )
                    }
                    if (heard == AssistantActivity.NOTHING_HEARD) {
                        Text("I didn't catch that, Boss.", color = PanelColors.Dim)
                    }
                }
            }
            if (talking) {
                val scroll = rememberScrollState()
                LaunchedEffect(replyShown, heard, past.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(scroll)
                        .clickable(remember { MutableInteractionSource() }, null) { touch() },
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
                ) {
                    past.forEachIndexed { i, t ->
                        HudLine(t.heard, mine = true)
                        // the newest answer types out
                        HudLine(if (i == past.lastIndex && t.reply == reply) replyShown else t.reply, mine = false)
                    }
                    if (live && heard != AssistantActivity.NOTHING_HEARD) {
                        HudLine(heard, mine = true)
                        if (reply.isNotBlank()) HudLine(replyShown, mine = false)
                    }
                }
            }
            val last = past.lastOrNull()
            if (last != null && !live && !fixing) {
                // just right or wrong; wrong answers are saved with the question and log for later diagnosis
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HudChip(if (rated) "✓ Thanks" else "✓ Right", enabled = !rated && reported != last.id, selected = rated) { touch(); rated = true; vm.right() }
                    HudChip(if (reported == last.id) "✗ Saved" else "✗ Wrong", enabled = reported != last.id && !rated, selected = reported == last.id) { touch(); vm.wrongAnswer("") }
                    HudChip("✎ Wrong text") { touch(); fixText = last.heard; fixing = true }
                    HudChip("I didn't call") { vm.notCalled(); close() }
                }
            }
            if (fixing) {
                OutlinedTextField(
                    value = fixText, onValueChange = { touch(); fixText = it },
                    label = { Text("What did you say?") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { fixing = false; touch(); vm.fix(fixText.trim()) }, enabled = fixText.isNotBlank()) {
                        Text("Fix and run")
                    }
                    TextButton(onClick = { fixing = false }) { Text("Cancel") }
                }
            }
            HudInput(typed, { touch(); typed = it }) {
                touch()
                if (typed.isBlank()) vm.listen() else { vm.ask(typed.trim()); typed = "" }
            }
        }
    }
}

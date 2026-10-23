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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
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

/**
 * The assistant panel (replaces the Google Assistant / Gemini sheet, D-036): full-screen see-through glass over a blurred
 * picture of the screen, with Jimi's living Möbius strip (D-050). Opens on the wake word, also on the lock screen.
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
    val stripSize by animateDpAsState(if (talking) 120.dp else 200.dp, tween(if (motion) 450 else 0), label = "strip size")

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

    // see-through glass (D-050): blurred screen behind + dark tint; a tap on empty glass closes
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = fade }
            .clickable(remember { MutableInteractionSource() }, null, onClick = ::close),
    ) {
        backdrop?.let {
            Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.High)
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Hud.SheetBottom.copy(alpha = if (backdrop != null) 0.25f else 0.72f),
                    1f to Hud.SheetBottom.copy(alpha = if (backdrop != null) 0.6f else 0.9f),
                ),
            ),
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
                .animateContentSize(tween(if (motion) 200 else 0)),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (phase == JarvisCoreService.PHASE_SPEAKING) {
                    TextButton(onClick = { touch(); vm.shutUp() }) { Text("STOP", color = PanelColors.Cyan, letterSpacing = 2.sp) }
                }
                TextButton(onClick = ::close) { Text("✕", color = PanelColors.Dim, fontSize = 18.sp) }
            }
            Column(
                Modifier.fillMaxWidth().then(if (talking) Modifier else Modifier.weight(1f)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
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
            if (talking) {
                val scroll = rememberScrollState()
                LaunchedEffect(replyShown, heard, past.size) { scroll.animateScrollTo(scroll.maxValue) }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll)
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
                    HudChip(if (rated) "✓ Thanks" else "✓ Right", enabled = !rated && reported != last.id) { touch(); rated = true; vm.right() }
                    HudChip(if (reported == last.id) "✗ Saved" else "✗ Wrong", enabled = reported != last.id && !rated) { touch(); vm.wrongAnswer("") }
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

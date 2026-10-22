package com.alrasin.jarvis.ui

import android.animation.ValueAnimator
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.service.JarvisCoreService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference

/**
 * The assistant panel (replaces the Google Assistant / Gemini sheet, D-036): slides up from the bottom with a living orb. Opens on the wake word, also on the lock screen.
 * Shows live text and the answer, and lets the user fix wrong text without a pop-up. Closes itself
 * [IDLE_MS] after the conversation ends and nothing was touched.
 */
@AndroidEntryPoint
class AssistantActivity : ComponentActivity() {
    private val vm: PanelViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
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
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    companion object {
        private var current: WeakReference<AssistantActivity>? = null
        fun hideForScreenAgent() { current?.get()?.finish() }
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
    var reporting by remember { mutableStateOf(false) }
    var reportText by remember { mutableStateOf("") }
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

    fun close() { shown = false; closing = true }
    fun touch() { touched = System.currentTimeMillis() }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(closing) {
        if (closing) {
            delay(if (motion) 200 else 0)
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

    // tap on the dim area closes the panel
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
            .clickable(remember { MutableInteractionSource() }, null, onClick = ::close),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = shown,
            enter = slideInVertically(tween(if (motion) 200 else 0)) { it } + fadeIn(tween(if (motion) 200 else 0)),
            exit = slideOutVertically(tween(if (motion) 200 else 0)) { it } + fadeOut(tween(if (motion) 200 else 0)),
        ) {
            Column(Modifier.fillMaxWidth().imePadding()) {
                val sheetShape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .border(1.dp, Hud.edgeBrush, sheetShape)
                        .clickable(remember { MutableInteractionSource() }, null) { touch() },
                    shape = sheetShape,
                    color = Color.Transparent,
                    contentColor = PanelColors.Text,
                ) {
                  Box(Modifier.background(Hud.sheetBrush)) {
                    Column(
                        Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 20.dp)
                            .animateContentSize(tween(if (motion) 200 else 0)),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.fillMaxWidth()) {
                            Box(
                                Modifier.align(Alignment.TopCenter).size(32.dp, 3.dp)
                                    .background(PanelColors.Dim.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
                            )
                            Column(
                                Modifier.fillMaxWidth().padding(top = 14.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                HudOrb(mood, level)
                                AnimatedContent(if (idle) "Jimi" else phase, label = "phase") { p ->
                                    HudLabel(p, active = !idle)
                                }
                            }
                            Row(Modifier.align(Alignment.TopEnd)) {
                                if (phase == JarvisCoreService.PHASE_SPEAKING) {
                                    TextButton(onClick = { touch(); vm.shutUp() }) { Text("STOP", color = PanelColors.Cyan, letterSpacing = 2.sp) }
                                }
                                TextButton(onClick = ::close) { Text("✕", color = PanelColors.Dim, fontSize = 16.sp) }
                            }
                        }
                        val scroll = rememberScrollState()
                        LaunchedEffect(replyShown, heard, past.size) { scroll.animateScrollTo(scroll.maxValue) }
                        Column(
                            Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(scroll),
                            verticalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            past.forEachIndexed { i, t ->
                                HudLine(t.heard, mine = true)
                                // the newest answer types out
                                HudLine(if (i == past.lastIndex && t.reply == reply) replyShown else t.reply, mine = false)
                            }
                            if (!live && past.isEmpty() && heard != AssistantActivity.NOTHING_HEARD) {
                                Text(
                                    "How can I help, Boss?",
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
                                    color = PanelColors.Text,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                                )
                            }
                            if (heard == AssistantActivity.NOTHING_HEARD) {
                                Text("I didn't catch that, Boss.", color = PanelColors.Dim, modifier = Modifier.padding(start = 14.dp))
                            } else if (live) {
                                HudLine(heard, mine = true)
                                if (reply.isNotBlank()) HudLine(replyShown, mine = false)
                            }
                        }
                        val last = past.lastOrNull()
                        if (last != null && !live && !fixing && !reporting) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                HudChip(if (rated) "✓ Thanks" else "✓ Right", enabled = !rated) { touch(); rated = true; vm.right() }
                                if (reported == last.id) {
                                    HudChip("✗ Saved", enabled = false) {}
                                } else {
                                    HudChip("✗ Wrong answer") { touch(); reportText = ""; reporting = true }
                                }
                                HudChip("✎ Wrong text") { touch(); fixText = last.heard; fixing = true }
                            }
                            HudChip("I didn't call") { vm.notCalled(); close() }
                        }
                        if (reporting) {
                            OutlinedTextField(
                                value = reportText, onValueChange = { touch(); reportText = it },
                                label = { Text("What should Jimi have done? (optional)") }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { reporting = false; touch(); vm.wrongAnswer(reportText.trim()) }) { Text("Save for later") }
                                TextButton(onClick = { reporting = false }) { Text("Cancel") }
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
            }
        }
    }
}

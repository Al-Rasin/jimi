package com.alrasin.jarvis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.darkColorScheme
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
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Panel(vm, onClose = ::finish)
            }
        }
    }

    companion object {
        const val IDLE_MS = 30_000L
    }
}

@Composable
private fun Panel(vm: PanelViewModel, onClose: () -> Unit) {
    val phase by vm.phase.collectAsStateWithLifecycle()
    val heard by vm.heard.collectAsStateWithLifecycle()
    val reply by vm.reply.collectAsStateWithLifecycle()
    val level by vm.micLevel.collectAsStateWithLifecycle()
    var fixing by remember { mutableStateOf(false) }
    var fixText by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var rated by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var shown by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val idle = phase == JarvisCoreService.PHASE_WAKE
    val mood = when (phase) {
        JarvisCoreService.PHASE_HEARING, JarvisCoreService.PHASE_FOLLOW_UP -> OrbMood.HEARING
        JarvisCoreService.PHASE_THINKING -> OrbMood.THINKING
        JarvisCoreService.PHASE_SPEAKING -> OrbMood.SPEAKING
        else -> OrbMood.IDLE
    }
    val replyShown = rememberTypewriter(reply)

    fun close() { shown = false; closing = true }
    fun touch() { touched = System.currentTimeMillis() }

    LaunchedEffect(Unit) { shown = true }
    LaunchedEffect(closing) {
        if (closing) {
            delay(260)   // let the sheet slide away first
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
            enter = slideInVertically(spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically(tween(240)) { it } + fadeOut(tween(240)),
        ) {
            Column(Modifier.fillMaxWidth().imePadding()) {
                GlowEdge(active = !idle, modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 24.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth()
                        .clickable(remember { MutableInteractionSource() }, null) { touch() },
                    shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                    color = PanelColors.Sheet,
                    contentColor = PanelColors.Text,
                ) {
                    Column(
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 18.dp).animateContentSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp)
                                .background(PanelColors.Dim.copy(alpha = 0.4f), RoundedCornerShape(2.dp)),
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Orb(mood, level, size = 60.dp)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                AnimatedContent(if (idle) "Jimi" else phase, label = "phase") { p ->
                                    Text(
                                        p,
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            brush = Brush.linearGradient(listOf(PanelColors.Cyan, PanelColors.Violet)),
                                            letterSpacing = 1.sp,
                                        ),
                                    )
                                }
                                Text(
                                    if (heard.isBlank()) "How can I help, Boss?" else heard,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Light),
                                    color = if (heard.isBlank()) PanelColors.Dim else PanelColors.Text,
                                )
                            }
                            if (phase == JarvisCoreService.PHASE_SPEAKING) {
                                TextButton(onClick = { touch(); vm.shutUp() }) { Text("Stop", color = PanelColors.Cyan) }
                            }
                            TextButton(onClick = ::close) { Text("✕", color = PanelColors.Dim, fontSize = 18.sp) }
                        }
                        if (replyShown.isNotBlank()) {
                            Text(
                                replyShown,
                                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                                modifier = Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                        if (heard.isNotBlank() && !fixing) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Chip(if (rated) "✓ Thanks" else "✓ Right", enabled = !rated) { touch(); rated = true; vm.right() }
                                Chip("✎ Wrong text") { touch(); fixText = heard; fixing = true }
                                Chip("I didn't call") { vm.notCalled(); close() }
                            }
                        }
                        if (fixing) {
                            OutlinedTextField(
                                value = fixText, onValueChange = { touch(); fixText = it },
                                label = { Text("What did you say?") }, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { fixing = false; touch(); vm.fix(fixText.trim()) }, enabled = fixText.isNotBlank()) {
                                    Text("Fix and run")
                                }
                                TextButton(onClick = { fixing = false }) { Text("Cancel") }
                            }
                        }
                        Row(
                            Modifier.fillMaxWidth().background(PanelColors.Field, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            BasicTextField(
                                value = typed, onValueChange = { touch(); typed = it }, singleLine = true,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = PanelColors.Text),
                                cursorBrush = SolidColor(PanelColors.Cyan),
                                modifier = Modifier.weight(1f).padding(vertical = 16.dp),
                                decorationBox = { inner ->
                                    if (typed.isEmpty()) Text("Type to Jimi", color = PanelColors.Dim)
                                    inner()
                                },
                            )
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(PanelColors.Cyan, PanelColors.Violet, PanelColors.Pink)))
                                    .clickable {
                                        touch()
                                        if (typed.isBlank()) vm.listen() else { vm.ask(typed.trim()); typed = "" }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(if (typed.isBlank()) "🎤" else "➤", color = Color.White, fontSize = 18.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) PanelColors.Text else PanelColors.Cyan,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .border(1.dp, PanelColors.Dim.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

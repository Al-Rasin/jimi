package com.alrasin.jarvis.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material3.OutlinedButton
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
 * The assistant panel (like the Google Assistant sheet). Opens on the wake word, also on the lock screen.
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
    var fixing by remember { mutableStateOf(false) }
    var fixText by remember { mutableStateOf("") }
    var typed by remember { mutableStateOf("") }
    var rated by remember { mutableStateOf(false) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val idle = phase == JarvisCoreService.PHASE_WAKE

    LaunchedEffect(heard) { rated = false }
    LaunchedEffect(idle, touched) {
        if (idle) {
            delay(AssistantActivity.IDLE_MS)
            onClose()
        }
    }
    fun touch() { touched = System.currentTimeMillis() }

    // tap on the dim area closes the panel
    Box(
        Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().imePadding()
                .clickable(remember { MutableInteractionSource() }, null) { touch() },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = Color(0xFF15181E),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (idle) "Jimi" else phase, color = Color(0xFF6FC3FF), style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f))
                    if (phase == JarvisCoreService.PHASE_SPEAKING) TextButton(onClick = { touch(); vm.shutUp() }) { Text("Stop") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
                if (heard.isNotBlank()) Text("“$heard”", style = MaterialTheme.typography.titleLarge)
                if (reply.isNotBlank()) {
                    Text(
                        reply,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp).verticalScroll(rememberScrollState()),
                    )
                }
                if (heard.isNotBlank() && !fixing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { touch(); rated = true; vm.right() }, enabled = !rated) { Text("✓ Right") }
                        OutlinedButton(onClick = { touch(); fixText = heard; fixing = true }) { Text("✗ Wrong text") }
                        TextButton(onClick = { vm.notCalled(); onClose() }) { Text("I didn't call") }
                    }
                }
                if (fixing) {
                    OutlinedTextField(
                        value = fixText, onValueChange = { touch(); fixText = it },
                        label = { Text("What did you say?") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { fixing = false; touch(); vm.fix(fixText.trim()) }, enabled = fixText.isNotBlank()) {
                            Text("Fix and run")
                        }
                        TextButton(onClick = { fixing = false }) { Text("Cancel") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed, onValueChange = { touch(); typed = it },
                        placeholder = { Text("Type to Jimi") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    if (typed.isBlank()) {
                        Button(onClick = { touch(); vm.listen() }) { Text("🎤") }
                    } else {
                        Button(onClick = { touch(); vm.ask(typed.trim()); typed = "" }) { Text("Send") }
                    }
                }
            }
        }
    }
}

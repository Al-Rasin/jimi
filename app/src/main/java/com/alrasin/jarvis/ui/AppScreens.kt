package com.alrasin.jarvis.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.NotificationLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ACCENT = Color(0xFF6FC3FF)
private val TABS = listOf("Chat", "History", "Alerts", "Notes", "Settings", "Voice")

@Composable
fun AppShell(vm: AppViewModel, home: HomeViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = {}, label = { Text(t) })
                }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> ChatTab(vm)
                1 -> HistoryTab(vm)
                2 -> AlertsTab(vm)
                3 -> NotesTab(vm)
                4 -> SettingsTab(vm)
                else -> HomeScreen(home)
            }
        }
    }
}

private val timeFmt = SimpleDateFormat("d MMM HH:mm", Locale.US)
private fun time(ms: Long) = timeFmt.format(Date(ms))

@Composable
private fun ChatTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        LazyColumn(Modifier.weight(1f), reverseLayout = true, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(turns.take(60), key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth()) {
                    Text(t.heard, color = ACCENT, modifier = Modifier.align(Alignment.End))
                    Text(t.reply, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type to Jimi") }, singleLine = true)
            Button(onClick = { vm.ask(text.trim()); text = "" }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}

@Composable
private fun HistoryTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("Fix wrong text here. Fixes are saved for training.", style = MaterialTheme.typography.bodySmall) }
        items(turns, key = { it.id }) { TurnCard(it, vm) }
    }
}

@Composable
private fun TurnCard(t: ConversationTurn, vm: AppViewModel) {
    var fixing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(t.heard) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text("${time(t.timeMs)} · ${t.source}${t.tool?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall)
            Text("“${t.heard}”", color = ACCENT)
            if (t.rawText.isNotBlank() && t.rawText != t.heard) Text("raw: ${t.rawText}", style = MaterialTheme.typography.bodySmall)
            Text(t.reply)
            when (t.textOk) {
                true -> Text("✓ text right", style = MaterialTheme.typography.labelSmall)
                false -> Text("✗ fixed to: ${t.correction ?: ""}", style = MaterialTheme.typography.labelSmall)
                null -> {}
            }
            Row {
                t.audioPath?.let { p -> TextButton(onClick = { vm.play(p) }) { Text("▶ Play") } }
                TextButton(onClick = { vm.rate(t.id, true) }) { Text("✓") }
                TextButton(onClick = { fixing = !fixing }) { Text("✗ Fix") }
            }
            if (fixing) {
                OutlinedTextField(fix, { fix = it }, Modifier.fillMaxWidth(), label = { Text("What you said") })
                Button(onClick = { fixing = false; vm.fix(t.id, fix.trim()) }, enabled = fix.isNotBlank()) { Text("Fix and run") }
            }
        }
    }
}

@Composable
private fun AlertsTab(vm: AppViewModel) {
    val list by vm.notificationList.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val ruleOf = rules.associate { it.pkg to it.mode }
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text("Tap Important / Noise to teach Jimi. Rules apply to the whole app.", style = MaterialTheme.typography.bodySmall) }
        items(list, key = { it.id }) { NotificationCard(it, ruleOf[it.pkg], vm) }
    }
}

@Composable
private fun NotificationCard(n: NotificationLog, rule: String?, vm: AppViewModel) {
    val important = n.label?.let { it == "important" } ?: n.important
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (important) Color(0xFF1E2A36) else Color(0xFF1A1A1A)),
    ) {
        Column(Modifier.padding(10.dp)) {
            Text("${time(n.timeMs)} · ${n.appName} · ${n.status} · ${n.reason}", style = MaterialTheme.typography.labelSmall)
            Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { vm.label(n.id, true) }) { Text(if (important) "• Important" else "Important") }
                TextButton(onClick = { vm.label(n.id, false) }) { Text(if (!important) "• Noise" else "Noise") }
                TextButton(onClick = { vm.setRule(n.pkg, if (rule == "keep") null else "keep") }) {
                    Text(if (rule == "keep") "App: always keep ✓" else "Always keep app")
                }
                TextButton(onClick = { vm.setRule(n.pkg, if (rule == "clear") null else "clear") }) {
                    Text(if (rule == "clear") "App: always clear ✓" else "Always clear")
                }
            }
        }
    }
}

@Composable
private fun NotesTab(vm: AppViewModel) {
    val notes by vm.noteList.collectAsStateWithLifecycle()
    val reminders by vm.reminderList.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Header("Reminders") }
        items(reminders, key = { "r${it.id}" }) { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${time(r.dueMs)}${if (r.repeat != "none") " (${r.repeat})" else ""} · ${r.text}${if (r.done) " ✓" else ""}",
                    Modifier.weight(1f),
                )
                TextButton(onClick = { vm.deleteReminder(r.id) }) { Text("Delete") }
            }
        }
        item { Header("Notes") }
        items(notes, key = { "n${it.id}" }) { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${time(n.timeMs)} · ${n.text}", Modifier.weight(1f))
                TextButton(onClick = { vm.deleteNote(n.id) }) { Text("Delete") }
            }
        }
        item { Header("What Jimi remembers") }
        items(facts, key = { "f${it.id}" }) { f ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.text, Modifier.weight(1f))
                TextButton(onClick = { vm.forget(f.id) }) { Text("Forget") }
            }
        }
    }
}

@Composable
private fun Header(t: String) = Text(t, color = ACCENT, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SettingsTab(vm: AppViewModel) {
    val followUp by vm.followUp.collectAsStateWithLifecycle()
    val panel by vm.showPanel.collectAsStateWithLifecycle()
    val autoClear by vm.autoClear.collectAsStateWithLifecycle()
    val voiceId by vm.voiceId.collectAsStateWithLifecycle()
    val city by vm.homeCity.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val enrollStatus by vm.enrollStatus.collectAsStateWithLifecycle()
    val voiceSamples by vm.voiceSamples.collectAsStateWithLifecycle()
    val speakerThreshold by vm.speakerThreshold.collectAsStateWithLifecycle()
    val lastVoiceScore by vm.lastVoiceScore.collectAsStateWithLifecycle()
    val models by vm.modelStatus.collectAsStateWithLifecycle()
    var cityText by remember(city) { mutableStateOf(city) }
    var pin by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("device_status") }
    var args by remember { mutableStateOf("{\"what\":\"battery\"}") }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Header("Setup")
        Text("Gives all permissions with root, turns on screen + notification access, stops MIUI killing Jimi.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::rootSetup) { Text("Set up with root") }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)

        Header("Conversation")
        Toggle("Keep listening after an answer (no \"Hey Jimi\" needed)", followUp, vm::setFollowUp)
        Toggle("Show panel on wake (also on lock screen)", panel, vm::setShowPanel)
        Toggle("Auto-clear unimportant notifications", autoClear, vm::setAutoClear)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(cityText, { cityText = it }, Modifier.weight(1f), label = { Text("Home city (weather)") }, singleLine = true)
            OutlinedButton(onClick = { vm.setHomeCity(cityText) }) { Text("Save") }
        }

        Header("Voice ID")
        Text(
            "When on and the phone is locked, calls, messages and unlock only work for your voice. " +
                "Record 3–5 times, in a normal voice, in different places. Say \"panic\" or \"code red\" to lock everything.",
            style = MaterialTheme.typography.bodySmall,
        )
        Toggle("Voice ID for risky actions when locked", voiceId, vm::setVoiceId)
        LaunchedEffect(enrollStatus) { vm.refreshVoice() }
        Text(
            "$voiceSamples recording(s) saved." + if (enrollStatus.isNotBlank()) " $enrollStatus" else "",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::enrollVoice) { Text("Record my voice (8 s)") }
            OutlinedButton(onClick = vm::resetVoice) { Text("Delete") }
        }
        Text(
            "Match needed: ${"%.2f".format(speakerThreshold)}" +
                (lastVoiceScore?.let { "   (last locked command: ${"%.2f".format(it)})" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(speakerThreshold, vm::setSpeakerThreshold, valueRange = 0.2f..0.8f, steps = 11)

        Header("Phone PIN (for \"unlock my phone\")")
        Text(if (pinSet) "PIN is saved, encrypted in Android Keystore." else "No PIN saved.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("PIN (empty = remove)") }, singleLine = true)
            OutlinedButton(onClick = { vm.setPin(pin); pin = "" }) { Text("Save") }
        }

        Header("Local AI models")
        Text(models, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::refreshModels) { Text("Check again") }

        Header("Data")
        Button(onClick = vm::export) { Text("Export all data (JSON)") }

        Header("Debug: run a tool")
        Text(vm.toolNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(tool, { tool = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Tool") }, singleLine = true)
        OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("Args JSON") })
        Button(onClick = { vm.runTool(tool, args) }) { Text("Run") }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = set)
    }
}

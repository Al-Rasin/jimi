package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import com.alrasin.jarvis.service.JarvisCoreService
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.NotificationLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TABS = listOf(
    "Chat" to Icons.Filled.Face, "History" to Icons.AutoMirrored.Filled.List, "Alerts" to Icons.Filled.Notifications,
    "Notes" to Icons.Filled.Edit, "Settings" to Icons.Filled.Settings, "Wake" to Icons.Filled.Star,
)
private val SUGGESTIONS = listOf("What's the weather?", "Set a timer for 5 minutes", "Battery level", "Good night")

@Composable
fun AppShell(vm: AppViewModel, home: HomeViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val running by home.running.collectAsStateWithLifecycle()
    val phase by home.phase.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Orb(if (running) moodOf(phase) else OrbMood.IDLE, 0f, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    GradientTitle("Jimi", 22)
                    Text(
                        if (running) (if (phase == JarvisCoreService.PHASE_WAKE) "Listening for \"Hey Jimi\"" else phase) else "Asleep",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    if (running) "On" else "Off",
                    color = if (running) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (running) EnergyBrush else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
                        .clickable { if (running) home.stop() else home.start() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TABS.forEachIndexed { i, (t, icon) ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icon, contentDescription = t) },
                        label = { Text(t, style = MaterialTheme.typography.labelSmall) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PanelColors.Cyan, selectedTextColor = PanelColors.Cyan,
                            indicatorColor = PanelColors.Cyan.copy(alpha = 0.12f),
                        ),
                    )
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChatTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).imePadding()) {
        if (turns.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Orb(OrbMood.IDLE, 0f, size = 160.dp)
                GradientTitle("Hi Boss", 28, Modifier.padding(top = 8.dp))
                Text("Say \"Hey Jimi\" or type below.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    Modifier.padding(top = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) { SUGGESTIONS.forEach { s -> Pill(s) { vm.ask(s) } } }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f), reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(turns.take(60), key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Bubble(t.heard, mine = true)
                        Bubble(t.reply, mine = false)
                    }
                }
            }
        }
        InputPill(text, { text = it }, Modifier.padding(bottom = 10.dp)) {
            if (text.isBlank()) vm.listen() else { vm.ask(text.trim()); text = "" }
        }
    }
}

/** Chat bubble: the user's words on the right with a gradient edge, Jimi's answer on the left next to a small orb. */
@Composable
fun Bubble(text: String, mine: Boolean) {
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) Orb(OrbMood.IDLE, 0f, size = 28.dp, modifier = Modifier.padding(end = 6.dp, top = 2.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = PanelColors.Text,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp))
                .then(
                    if (mine) Modifier.background(Brush.linearGradient(listOf(Color(0xFF173247), Color(0xFF2A1F4A))))
                    else Modifier.background(PanelColors.Field),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** Rounded text field with the gradient mic / send button. */
@Composable
fun InputPill(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier, onGo: () -> Unit) {
    Row(
        modifier.fillMaxWidth().background(PanelColors.Field, RoundedCornerShape(28.dp)).padding(start = 18.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text, onValueChange = onText, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = PanelColors.Text),
            cursorBrush = SolidColor(PanelColors.Cyan),
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Type to Jimi", color = PanelColors.Dim)
                inner()
            },
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(EnergyBrush).clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isBlank()) Text("🎤", fontSize = 18.sp)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = Color.White)
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) PanelColors.Cyan else PanelColors.Text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .border(1.dp, if (selected) PanelColors.Cyan.copy(alpha = 0.6f) else PanelColors.Dim.copy(alpha = 0.35f), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
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
    GlassCard {
        Column {
            Text("${time(t.timeMs)} · ${t.source}${t.tool?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall)
            Text("“${t.heard}”", color = PanelColors.Cyan)
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
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (important) Color(0xFF13202E) else Color(0xFF0E121B)),
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
private fun Header(t: String) = GradientTitle(t, 17, Modifier.padding(top = 10.dp))

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
    val cloudOn by vm.cloudEnabled.collectAsStateWithLifecycle()
    val cloudChat by vm.cloudForChat.collectAsStateWithLifecycle()
    val cloudFacts by vm.cloudSendFacts.collectAsStateWithLifecycle()
    val cloudKeySet by vm.cloudKeySet.collectAsStateWithLifecycle()
    var cloudKey by remember { mutableStateOf("") }
    val models by vm.modelStatus.collectAsStateWithLifecycle()
    var cityText by remember(city) { mutableStateOf(city) }
    var pin by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("device_status") }
    var args by remember { mutableStateOf("{\"what\":\"battery\"}") }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(title = "Setup") {
            Text("Gives all permissions with root, turns on screen + notification access, stops MIUI killing Jimi.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::rootSetup) { Text("Set up with root") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
        GlassCard(title = "Conversation") {
            Toggle("Keep listening after an answer (no \"Hey Jimi\" needed)", followUp, vm::setFollowUp)
            Toggle("Show panel on wake (also on lock screen)", panel, vm::setShowPanel)
            Toggle("Auto-clear unimportant notifications", autoClear, vm::setAutoClear)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cityText, { cityText = it }, Modifier.weight(1f), label = { Text("Home city (weather)") }, singleLine = true)
                OutlinedButton(onClick = { vm.setHomeCity(cityText) }) { Text("Save") }
            }
        }
        GlassCard(title = "Voice ID") {
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
        }
        GlassCard(title = "Phone PIN (for \"unlock my phone\")") {
            Text(if (pinSet) "PIN is saved, encrypted in Android Keystore." else "No PIN saved.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("PIN (empty = remove)") }, singleLine = true)
                OutlinedButton(onClick = { vm.setPin(pin); pin = "" }) { Text("Save") }
            }
        }
        GlassCard(title = "Cloud AI (optional, off by default)") {
            Text(
                "Uses Claude (needs internet + an Anthropic API key). Phone actions always stay on the phone; " +
                    "only your words go out. Say \"think hard …\" to ask the cloud once.",
                style = MaterialTheme.typography.bodySmall,
            )
            Toggle("Use cloud AI", cloudOn, vm::setCloudEnabled)
            Toggle("Send all chat to the cloud (not only \"think hard\")", cloudChat, vm::setCloudForChat)
            Toggle("Also send what Jimi remembers about me", cloudFacts, vm::setCloudSendFacts)
            Text(if (cloudKeySet) "API key is saved, encrypted." else "No API key saved.", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    cloudKey, { cloudKey = it.trim() }, Modifier.weight(1f), label = { Text("API key (empty = remove)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedButton(onClick = { vm.setCloudKey(cloudKey); cloudKey = "" }) { Text("Save") }
            }
        }
        GlassCard(title = "Local AI models") {
            Text(models, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = vm::refreshModels) { Text("Check again") }
        }
        GlassCard(title = "Data") {
            Button(onClick = vm::export) { Text("Export all data (JSON)") }
        }
        GlassCard(title = "Debug: run a tool") {
            Text(vm.toolNames.joinToString(", "), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(tool, { tool = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Tool") }, singleLine = true)
            OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("Args JSON") })
            Button(onClick = { vm.runTool(tool, args) }) { Text("Run") }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = set)
    }
}

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.sp
import com.alrasin.jarvis.voice.Engine
import com.alrasin.jarvis.voice.Voice
import com.alrasin.jarvis.voice.VoicePack
import com.alrasin.jarvis.voice.GeminiVoice
import com.alrasin.jarvis.voice.Voices
import com.alrasin.jarvis.service.JarvisCoreService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.llm.ChatModels
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
    val setupComplete by vm.setupComplete.collectAsStateWithLifecycle()
    var setupOpen by rememberSaveable { mutableStateOf(false) }
    var deviceCheckOpen by rememberSaveable { mutableStateOf(false) }
    if (setupComplete == null) return
    if (setupComplete == false || setupOpen) {
        SetupWizard(vm, home) { vm.finishSetup(); setupOpen = false }
        return
    }
    if (deviceCheckOpen) {
        DeviceCheckScreen(vm) { deviceCheckOpen = false }
        return
    }
    val pager = rememberPagerState { TABS.size }
    val running by home.running.collectAsStateWithLifecycle()
    val phase by home.phase.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MobiusStrip(if (running) moodOf(phase) else OrbMood.IDLE, size = 44.dp)
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
                    color = if (running) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (running) PanelColors.Cyan else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { if (running) home.stop() else home.start() }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        },
        bottomBar = {
            MobiusNav(TABS, pager)
        },
    ) { pad ->
        // swipe between pages; the Möbius bar rolls along (D-050)
        HorizontalPager(pager, Modifier.padding(pad).fillMaxSize(), key = { TABS[it].first }) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> ChatTab(vm)
                    1 -> HistoryTab(vm)
                    2 -> AlertsTab(vm)
                    3 -> NotesTab(vm)
                    4 -> SettingsTab(vm, { setupOpen = true }, { deviceCheckOpen = true })
                    else -> HomeScreen(home)
                }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).imePadding()) {
        if (turns.isEmpty()) {
            Column(
                Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                MobiusStrip(OrbMood.IDLE, size = 180.dp)
                GradientTitle("Hi Boss", 34, Modifier.padding(top = 8.dp))
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
                verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(turns.take(60), key = { it.id }) { t ->
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

/** User messages align right; assistant messages align left. */
@Composable
fun Bubble(text: String, mine: Boolean) {
    if (text.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!mine) MobiusStrip(OrbMood.IDLE, size = 28.dp, modifier = Modifier.padding(end = 6.dp, top = 2.dp), still = true)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = PanelColors.Text,
            modifier = Modifier.widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp))
                .then(
                    if (mine) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    else Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow),
                )
                .padding(16.dp),
        )
    }
}

/** Rounded text field with the mic / send button. */
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
            Modifier.size(48.dp).clip(CircleShape).background(PanelColors.Cyan).clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isBlank()) Text("🎤", fontSize = 18.sp)
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text,
        color = if (selected) PanelColors.Cyan else PanelColors.Text,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.clip(RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) PanelColors.Cyan else MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

@Composable
private fun HistoryTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("✗ Wrong saves the answer for checking. ✎ fixes misheard text (saved for training).", style = MaterialTheme.typography.bodySmall) }
        items(turns, key = { it.id }) { TurnCard(it, vm) }
    }
}

@Composable
private fun TurnCard(t: ConversationTurn, vm: AppViewModel) {
    var fixing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(t.heard) }
    var wrong by remember { mutableStateOf(false) }
    GlassCard {
        Column {
            Text("${time(t.timeMs)} · ${t.source}${t.tool?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = JimiMono))
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
                TextButton(onClick = { vm.rate(t.id, true) }) { Text("✓ Right") }
                TextButton(onClick = { wrong = true; vm.wrongAnswer(t) }, enabled = !wrong) { Text(if (wrong) "✗ Saved" else "✗ Wrong") }
                TextButton(onClick = { fixing = !fixing }) { Text("✎ Wrong text") }
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
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Tap Important / Noise to teach Jimi. Rules apply to the whole app.", style = MaterialTheme.typography.bodySmall) }
        items(list, key = { it.id }) { NotificationCard(it, ruleOf[it.pkg], vm) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationCard(n: NotificationLog, rule: String?, vm: AppViewModel) {
    val important = n.label?.let { it == "important" } ?: n.important
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (important) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("${time(n.timeMs)} · ${n.appName} · ${n.status} · ${n.reason}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = JimiMono))
            Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(n.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    LazyColumn(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun Header(t: String) = GradientTitle(t, 22, Modifier.padding(top = 12.dp))

@Composable
private fun SettingsTab(vm: AppViewModel, onSetup: () -> Unit, onDeviceCheck: () -> Unit) {
    val spokenWake by vm.spokenWake.collectAsStateWithLifecycle()
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
    val chatModel by vm.chatModel.collectAsStateWithLifecycle()
    LaunchedEffect(chatModel) { vm.refreshModels() }
    var cityText by remember(city) { mutableStateOf(city) }
    var pin by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("device_status") }
    var args by remember { mutableStateOf("{\"what\":\"battery\"}") }

    Column(Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassCard(title = "Setup") {
            Button(onClick = onSetup) { Text("Run setup wizard") }
            OutlinedButton(onClick = onDeviceCheck) { Text("Device check") }
            Text("Root is optional. Extra permissions and background settings depend on this phone.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = vm::rootSetup) { Text("Set up with root") }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
        AssistantModeCard(vm)
        GlassCard(title = "Conversation") {
            SpeechEngineSetting()
            SecondPassSetting()
            Toggle("Spoken wake acknowledgement (off for beep)", spokenWake, vm::setSpokenWake)
            Toggle("Keep listening after an answer (no \"Hey Jimi\" needed)", followUp, vm::setFollowUp)
            Toggle("Show panel on wake (also on lock screen)", panel, vm::setShowPanel)
            Toggle("Auto-clear unimportant notifications", autoClear, vm::setAutoClear)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cityText, { cityText = it }, Modifier.weight(1f), label = { Text("Home city (weather)") }, singleLine = true)
                OutlinedButton(onClick = { vm.setHomeCity(cityText) }) { Text("Save") }
            }
        }
        VoiceCard(vm)
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
                "Uses Gemini (Google key, starts with AIza) or Claude (Anthropic key); needs internet. Phone actions " +
                    "always run on the phone; only your words go out. With \"send all chat\" on, the cloud also picks " +
                    "actions fast and answers web questions. Say \"think hard …\" to ask the cloud once.",
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
            listOf(ChatModels.DEFAULT to "Default (Qwen3 / Gemma)", ChatModels.LFM to "LFM2.5 1.2B Instruct").forEach { (id, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chatModel == id, onClick = { vm.setChatModel(id) })
                    Text(label, Modifier.clickable { vm.setChatModel(id) })
                }
            }
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

/** Pick Jimi's voice (D-038, D-053): the phone's Google voices, Piper voices, and the online Gemini voice. */
@Composable
internal fun VoiceCard(vm: AppViewModel) {
    val current by vm.voice.collectAsStateWithLifecycle()
    val speed by vm.voiceSpeed.collectAsStateWithLifecycle()
    val installed by vm.installedPacks.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val trying by vm.trying.collectAsStateWithLifecycle()
    val phoneVoices by vm.phoneVoices.collectAsStateWithLifecycle()
    val cloudVoice by vm.cloudVoice.collectAsStateWithLifecycle()
    val cloudVoiceName by vm.cloudVoiceName.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(open) { if (open) vm.loadPhoneVoices() }

    @Composable
    fun PackBar(pack: VoicePack) {
        val p = downloads[pack.id]
        when {
            p != null -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
            pack.id == Voices.BUILT_IN -> {}
            pack.id in installed -> TextButton(onClick = { vm.deleteVoice(pack) }) { Text("Delete (${pack.sizeMb} MB)") }
            else -> OutlinedButton(onClick = { vm.downloadVoice(pack) }) { Text("Download (${pack.sizeMb} MB)") }
        }
    }

    @Composable
    fun VoiceRow(v: Voice) {
        val ready = v.pack.engine == Engine.ANDROID || v.pack.id in installed
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                (if (v.id == current) "● " else "") + v.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (v.id == current) FontWeight.Bold else null,
            )
            if (ready) {
                TextButton(onClick = { vm.tryVoice(v) }, enabled = trying == null) { Text(if (trying == v.id) "…" else "Try") }
                if (v.id != current) TextButton(onClick = { vm.setVoice(v.id) }) { Text("Use") }
            }
        }
    }

    GlassCard(title = "Voice") {
        Text("Now: ${Voices.byId(current).label}", style = MaterialTheme.typography.bodySmall)
        Toggle("Online voice (Gemini) for cloud answers", cloudVoice, vm::setCloudVoice)
        if (cloudVoice) Text("Now online: ${GeminiVoice.byId(cloudVoiceName).label}. Needs cloud AI + a paid Gemini key (free keys run out fast); adds ~3 s.",
            style = MaterialTheme.typography.bodySmall)
        Text("Speed ${"%.2f".format(speed)}x", style = MaterialTheme.typography.bodySmall)
        Slider(speed, vm::setVoiceSpeed, valueRange = 0.8f..1.3f, steps = 9)
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide voices" else "Change voice") }
        if (open) {
            val fast = Voices.ALL.filter { it.pack.engine == Engine.PIPER }
            Text("Phone voices (Google, offline)", fontWeight = FontWeight.Bold)
            if (phoneVoices.isEmpty()) Text("Loading… (needs Google Speech Services with English voice data)", style = MaterialTheme.typography.bodySmall)
            phoneVoices.forEach { VoiceRow(it) }
            Text("Online voice (Gemini)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            GeminiVoice.VOICES.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (c.id == cloudVoiceName) "● " else "") + c.label, Modifier.weight(1f),
                        fontWeight = if (c.id == cloudVoiceName) FontWeight.Bold else null)
                    TextButton(onClick = { vm.tryCloudVoice(c.id) }, enabled = trying == null) { Text(if (trying == c.id) "…" else "Try") }
                    if (c.id != cloudVoiceName) TextButton(onClick = { vm.setCloudVoiceName(c.id) }) { Text("Use") }
                }
            }
            Text("Fast (Piper)", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            fast.forEach { v ->
                VoiceRow(v)
                PackBar(v.pack)
            }
        }
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, set: (Boolean) -> Unit) {
    var checked by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = { checked = it; set(it) })
    }
}

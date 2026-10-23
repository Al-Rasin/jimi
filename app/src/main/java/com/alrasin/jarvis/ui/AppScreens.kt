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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.res.painterResource
import com.alrasin.jarvis.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import com.alrasin.jarvis.voice.Engine
import com.alrasin.jarvis.voice.Voice
import com.alrasin.jarvis.voice.VoicePack
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.VisualTransformation
import com.alrasin.jarvis.core.Secrets
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
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MobiusStrip(if (running) moodOf(phase) else OrbMood.IDLE, size = 40.dp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    GradientTitle("Jimi", 20)
                    Meta(if (running) (if (phase == JarvisCoreService.PHASE_WAKE) "Listening for \"Hey Jimi\"" else phase) else "Asleep")
                }
                // one tap turns listening on or off
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .background(JimiColors.Surface2)
                        .border(1.dp, if (running) JimiColors.Cyan.copy(alpha = 0.5f) else JimiColors.Outline, RoundedCornerShape(50))
                        .clickable { if (running) home.stop() else home.start() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatusDot(running)
                    Text(if (running) "On" else "Off", style = MaterialTheme.typography.labelLarge, color = if (running) JimiColors.Text else JimiColors.SecondaryText)
                }
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

/** Same list frame on every tab: 20 dp sides, 12 dp between cards, room at the bottom. */
@Composable
private fun TabList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) = LazyColumn(
    Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
)

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
                MobiusStrip(OrbMood.IDLE, size = 160.dp)
                GradientTitle("Hi Boss", 30, Modifier.padding(top = 12.dp))
                Hint("Say \"Hey Jimi\" or type below.", Modifier.padding(top = 4.dp))
                FlowRow(
                    Modifier.padding(top = 24.dp),
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
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Bubble(t.heard, mine = true)
                        Bubble(t.reply, mine = false)
                    }
                }
            }
        }
        InputPill(text, { text = it }, Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            if (text.isBlank()) vm.listen() else { vm.ask(text.trim()); text = "" }
        }
    }
}

/** User messages align right with a soft cyan tint; Jimi's align left on a dark card. */
@Composable
fun Bubble(text: String, mine: Boolean) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = if (mine) 20.dp else 6.dp, bottomEnd = if (mine) 6.dp else 20.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
        if (!mine) MobiusStrip(OrbMood.IDLE, size = 26.dp, modifier = Modifier.padding(end = 8.dp, bottom = 4.dp), still = true)
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = PanelColors.Text,
            modifier = Modifier.widthIn(max = 290.dp)
                .clip(shape)
                .background(if (mine) JimiColors.Cyan.copy(alpha = 0.12f) else JimiColors.Surface1)
                .border(1.dp, if (mine) JimiColors.Cyan.copy(alpha = 0.18f) else JimiColors.Outline.copy(alpha = 0.6f), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Rounded text field with the mic / send button. */
@Composable
fun InputPill(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier, onGo: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .background(JimiColors.Surface1, RoundedCornerShape(28.dp))
            .border(1.dp, JimiColors.Outline, RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text, onValueChange = onText, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = PanelColors.Text),
            cursorBrush = SolidColor(PanelColors.Cyan),
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Type to Jimi", color = JimiColors.TertiaryText)
                inner()
            },
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(PanelColors.Cyan).clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isBlank()) Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak", tint = JimiColors.OnCyan, modifier = Modifier.size(22.dp))
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = JimiColors.OnCyan, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) = Chip(text, selected, onClick = onClick)

@Composable
private fun HistoryTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    TabList {
        item { PageTitle("History", "Wrong saves the answer for checking. Fix text corrects what Jimi heard (saved for training).") }
        if (turns.isEmpty()) item { EmptyState("No talks yet", "Say \"Hey Jimi\" to start.") }
        items(turns, key = { it.id }) { TurnCard(it, vm) }
    }
}

@Composable
private fun TurnCard(t: ConversationTurn, vm: AppViewModel) {
    var fixing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(t.heard) }
    var wrong by remember { mutableStateOf(false) }
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Meta("${time(t.timeMs)} · ${t.source}${t.tool?.let { " · $it" } ?: ""}")
            Text("“${t.heard}”", color = PanelColors.Cyan, style = MaterialTheme.typography.bodyMedium)
            if (t.rawText.isNotBlank() && t.rawText != t.heard) Hint("Heard: ${t.rawText}")
            Text(t.reply, style = MaterialTheme.typography.bodyMedium)
            when (t.textOk) {
                true -> Hint("✓ Text was right")
                false -> Hint("Fixed to: ${t.correction ?: ""}")
                null -> {}
            }
            ChipRow {
                t.audioPath?.let { p -> Chip("▶ Play") { vm.play(p) } }
                Chip("✓ Right", selected = t.textOk == true) { vm.rate(t.id, true) }
                Chip(if (wrong) "Saved" else "✗ Wrong", enabled = !wrong) { wrong = true; vm.wrongAnswer(t) }
                Chip("✎ Fix text", selected = fixing) { fixing = !fixing }
            }
            if (fixing) {
                OutlinedTextField(fix, { fix = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("What you said") })
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
    TabList {
        item { PageTitle("Alerts", "Tap Important or Noise to teach Jimi. Keep app / Clear app sets a rule for the whole app.") }
        if (list.isEmpty()) item { EmptyState("No alerts yet", "New notifications show up here.") }
        items(list, key = { it.id }) { NotificationCard(it, ruleOf[it.pkg], vm) }
    }
}

@Composable
private fun NotificationCard(n: NotificationLog, rule: String?, vm: AppViewModel) {
    val important = n.label?.let { it == "important" } ?: n.important
    GlassCard {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusDot(important)
                Text(n.appName, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Meta(time(n.timeMs))
            }
            Text(n.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (n.text.isNotBlank()) Text(n.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Meta("${n.status} · ${n.reason}")
            ChipRow {
                Chip("Important", selected = important) { vm.label(n.id, true) }
                Chip("Noise", selected = !important) { vm.label(n.id, false) }
                Chip("Keep app", selected = rule == "keep") { vm.setRule(n.pkg, if (rule == "keep") null else "keep") }
                Chip("Clear app", selected = rule == "clear") { vm.setRule(n.pkg, if (rule == "clear") null else "clear") }
            }
        }
    }
}

@Composable
private fun NotesTab(vm: AppViewModel) {
    val notes by vm.noteList.collectAsStateWithLifecycle()
    val reminders by vm.reminderList.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    TabList {
        item { PageTitle("Notes", "Reminders, notes and what Jimi remembers about you.") }
        item {
            ListCard("Reminders", reminders, "No reminders. Say \"remind me…\"") { r ->
                ItemRow(
                    r.text + if (r.done) " ✓" else "",
                    time(r.dueMs) + if (r.repeat != "none") " · ${r.repeat}" else "",
                ) { Chip("Delete") { vm.deleteReminder(r.id) } }
            }
        }
        item {
            ListCard("Notes", notes, "No notes. Say \"note that…\"") { n ->
                ItemRow(n.text, time(n.timeMs)) { Chip("Delete") { vm.deleteNote(n.id) } }
            }
        }
        item {
            ListCard("What Jimi remembers", facts, "Nothing yet. Say \"remember that…\"") { f ->
                ItemRow(f.text) { Chip("Forget") { vm.forget(f.id) } }
            }
        }
    }
}

/** One card holding a short list, with thin lines between rows. */
@Composable
private fun <T> ListCard(title: String, rows: List<T>, empty: String, row: @Composable (T) -> Unit) {
    GlassCard(title = title) {
        Column {
            if (rows.isEmpty()) Hint(empty)
            rows.forEachIndexed { i, r ->
                if (i > 0) RowDivider()
                row(r)
            }
        }
    }
}

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
    val savedKeys by vm.savedKeys.collectAsStateWithLifecycle()
    val models by vm.modelStatus.collectAsStateWithLifecycle()
    val chatModel by vm.chatModel.collectAsStateWithLifecycle()
    LaunchedEffect(chatModel) { vm.refreshModels() }
    var cityText by remember(city) { mutableStateOf(city) }
    var pin by remember { mutableStateOf("") }
    var tool by remember { mutableStateOf("device_status") }
    var args by remember { mutableStateOf("{\"what\":\"battery\"}") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageTitle("Settings")
        GlassCard(title = "Conversation") {
            SpeechEngineSetting()
            RowDivider()
            SecondPassSetting()
            RowDivider()
            ToggleRow("Spoken wake reply", spokenWake, "Off = a short beep") { vm.setSpokenWake(it) }
            ToggleRow("Keep listening after an answer", followUp, "No \"Hey Jimi\" needed for the next question") { vm.setFollowUp(it) }
            ToggleRow("Show panel on wake", panel, "Also on the lock screen") { vm.setShowPanel(it) }
            ToggleRow("Auto-clear unimportant alerts", autoClear) { vm.setAutoClear(it) }
            RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cityText, { cityText = it }, Modifier.weight(1f), label = { Text("Home city (weather)") }, singleLine = true)
                FilledTonalButton(onClick = { vm.setHomeCity(cityText) }) { Text("Save") }
            }
        }
        VoiceCard(vm)
        GlassCard(title = "Voice ID") {
            Hint(
                "When on and the phone is locked, calls, messages and unlock only work for your voice. " +
                    "Record 3–5 times, in a normal voice, in different places. Say \"panic\" or \"code red\" to lock everything.",
            )
            ToggleRow("Voice ID for risky actions when locked", voiceId) { vm.setVoiceId(it) }
            LaunchedEffect(enrollStatus) { vm.refreshVoice() }
            Hint("$voiceSamples recording(s) saved." + if (enrollStatus.isNotBlank()) " $enrollStatus" else "")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::enrollVoice) { Text("Record my voice (8 s)") }
                FilledTonalButton(onClick = vm::resetVoice) { Text("Delete") }
            }
            Hint(
                "Match needed: ${"%.2f".format(speakerThreshold)}" +
                    (lastVoiceScore?.let { "   (last locked command: ${"%.2f".format(it)})" } ?: ""),
            )
            Slider(speakerThreshold, vm::setSpeakerThreshold, valueRange = 0.2f..0.8f, steps = 11)
        }
        GlassCard(title = "Phone PIN") {
            Hint(if (pinSet) "Saved, encrypted in Android Keystore. Used for \"unlock my phone\"." else "No PIN saved. Needed for \"unlock my phone\".")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("PIN (empty = remove)") }, singleLine = true)
                FilledTonalButton(onClick = { vm.setPin(pin); pin = "" }) { Text("Save") }
            }
        }
        GlassCard(title = "Cloud AI") {
            Hint(
                "Optional, off by default. Free keys work: Groq, Gemini (Google key, starts with AIza), OpenRouter, Cloudflare. Jimi tries them " +
                    "in this order and uses the next one when a free limit is reached. Claude keys go in the Gemini box. " +
                    "Needs internet. Phone actions always run on the phone; only your words go out. With \"send all " +
                    "chat\" on, the cloud also picks actions fast and answers web questions. Say \"think hard …\" to " +
                    "ask the cloud once.",
            )
            ToggleRow("Use cloud AI", cloudOn) { vm.setCloudEnabled(it) }
            ToggleRow("Send all chat to the cloud", cloudChat, "Not only \"think hard\"") { vm.setCloudForChat(it) }
            ToggleRow("Also send what Jimi remembers about me", cloudFacts) { vm.setCloudSendFacts(it) }
            CLOUD_KEY_ROWS.forEach { row ->
                RowDivider()
                KeyRow(row, row.secret in savedKeys, onSave = { vm.setKey(row.secret, row.label, it) }, onTest = { vm.testKey(row.secret) })
            }
            if (message.isNotEmpty()) Hint(message)
        }
        GlassCard(title = "Local AI model") {
            listOf(ChatModels.DEFAULT to "Default (Qwen3 / Gemma)", ChatModels.LFM to "LFM2.5 1.2B Instruct").forEach { (id, label) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setChatModel(id) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = chatModel == id, onClick = { vm.setChatModel(id) })
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Hint(models)
            ChipRow { Chip("Check again", onClick = vm::refreshModels) }
        }
        AssistantModeCard(vm)
        GlassCard(title = "Setup") {
            Hint("Root is optional. Extra permissions and background settings depend on this phone.")
            ChipRow {
                Chip("Run setup wizard", onClick = onSetup)
                Chip("Device check", onClick = onDeviceCheck)
                Chip("Set up with root", onClick = vm::rootSetup)
                Chip("Export all data (JSON)", onClick = vm::export)
            }
            if (message.isNotBlank()) Hint(message)
        }
        GlassCard(title = "Debug: run a tool") {
            Hint(vm.toolNames.joinToString(", "))
            OutlinedTextField(tool, { tool = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Tool") }, singleLine = true)
            OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("Args JSON") })
            FilledTonalButton(onClick = { vm.runTool(tool, args) }) { Text("Run") }
        }
    }
}

/** Pick Jimi's voice (D-054): the four best Google voices, plus built-in Alan as backup. Same voice online and offline. */
@Composable
internal fun VoiceCard(vm: AppViewModel) {
    val current by vm.voice.collectAsStateWithLifecycle()
    val speed by vm.voiceSpeed.collectAsStateWithLifecycle()
    val installed by vm.installedPacks.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val trying by vm.trying.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }

    @Composable
    fun PackBar(pack: VoicePack) {
        val p = downloads[pack.id]
        when {
            p != null -> LinearProgressIndicator({ p }, Modifier.fillMaxWidth())
            pack.id == Voices.BUILT_IN -> {}
            pack.id in installed -> ChipRow { Chip("Delete (${pack.sizeMb} MB)") { vm.deleteVoice(pack) } }
            else -> ChipRow { Chip("Download (${pack.sizeMb} MB)") { vm.downloadVoice(pack) } }
        }
    }

    @Composable
    fun VoiceRow(v: Voice) {
        val ready = v.pack.engine == Engine.ANDROID || v.pack.id in installed
        Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                (if (v.id == current) "● " else "") + v.label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (v.id == current) FontWeight.Bold else null,
            )
            if (ready) {
                Chip(if (trying == v.id) "…" else "Try", enabled = trying == null) { vm.tryVoice(v) }
                if (v.id != current) Chip("Use", selected = true) { vm.setVoice(v.id) }
            }
        }
    }

    GlassCard(title = "Voice") {
        ItemRow(Voices.byId(current).label, "Speed ${"%.2f".format(speed)}x") {
            Chip(if (open) "Hide voices" else "Change", selected = open) { open = !open }
        }
        Slider(speed, vm::setVoiceSpeed, valueRange = 0.8f..1.3f, steps = 9)
        if (open) {
            Voices.PHONE.forEach { VoiceRow(it) }
            Meta("Backup (if Google speech is missing)", Modifier.padding(top = 8.dp))
            Voices.ALL.filter { it.pack.engine == Engine.PIPER }.forEach { v ->
                VoiceRow(v)
                PackBar(v.pack)
            }
        }
    }
}

/** One cloud key box. [hidden] = shown as dots (keys); the Cloudflare account ID is not secret. */
private data class KeyRowSpec(val secret: String, val label: String, val hint: String, val hidden: Boolean = true, val testable: Boolean = true)

private val CLOUD_KEY_ROWS = listOf(
    KeyRowSpec(Secrets.GROQ_KEY, "Groq key", "starts with gsk_"),
    KeyRowSpec(Secrets.CLOUD_KEY, "Gemini / Claude key", "AIza… or sk-ant-…"),
    KeyRowSpec(Secrets.OPENROUTER_KEY, "OpenRouter key", "starts with sk-or-"),
    KeyRowSpec(Secrets.CLOUDFLARE_ACCOUNT, "Cloudflare account ID", "from the Cloudflare dashboard", hidden = false, testable = false),
    KeyRowSpec(Secrets.CLOUDFLARE_KEY, "Cloudflare token", "Workers AI token"),
)

@Composable
private fun KeyRow(spec: KeyRowSpec, saved: Boolean, onSave: (String) -> Unit, onTest: () -> Unit) {
    var value by remember { mutableStateOf("") }
    ItemRow(spec.label, if (saved) "Saved, encrypted" else "Not saved")
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, { value = it.trim() }, Modifier.weight(1f), label = { Text("${spec.hint} (empty = remove)") }, singleLine = true,
            visualTransformation = if (spec.hidden) PasswordVisualTransformation() else VisualTransformation.None,
        )
        FilledTonalButton(onClick = { onSave(value); value = "" }) { Text("Save") }
        if (spec.testable && saved) Chip("Test", onClick = onTest)
    }
}

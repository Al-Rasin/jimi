package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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
import com.alrasin.jarvis.agent.appmap.AppAskQueue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
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
    "Chat" to JimiIcons.Chat, "History" to JimiIcons.History, "Alerts" to JimiIcons.Alerts,
    "Notes" to JimiIcons.Notes, "Settings" to JimiIcons.Settings, "Wake" to JimiIcons.Wake,
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
            // home like a real assistant app: orb, small greeting, one big line, soft blue light from below (D-067)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(
                    Modifier.matchParentSize().drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                listOf(JimiColors.AccentDeep.copy(alpha = 0.20f), Color.Transparent),
                                center = Offset(size.width / 2f, size.height),
                                radius = size.width * 0.85f,
                            ),
                        )
                    },
                )
                Column(
                    Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    MobiusStrip(OrbMood.IDLE, size = 150.dp)
                    Text("Hi Boss", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodyLarge, color = JimiColors.SecondaryText)
                    GradientTitle("How can I help?", 32, Modifier.padding(top = 4.dp))
                    FlowRow(
                        Modifier.padding(top = 28.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { SUGGESTIONS.forEach { s -> Pill(s) { vm.ask(s) } } }
                }
            }
        } else {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    Modifier.fillMaxSize(), reverseLayout = true,
                    verticalArrangement = Arrangement.spacedBy(28.dp), contentPadding = PaddingValues(top = 24.dp, bottom = 12.dp),
                ) {
                    val newest = turns.firstOrNull()?.id
                    items(turns.take(60), key = { it.id }) { t ->
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Bubble(t.heard, mine = true)
                            Bubble(t.reply, mine = false, mark = t.id == newest)
                        }
                    }
                }
                // old messages fade out under the header instead of being cut hard
                Box(
                    Modifier.fillMaxWidth().height(28.dp)
                        .background(Brush.verticalGradient(listOf(JimiColors.Background, Color.Transparent))),
                )
            }
        }
        InputPill(text, { text = it }, Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            if (text.isBlank()) vm.listen() else { vm.ask(text.trim()); text = "" }
        }
    }
}

/**
 * Your words: small grey pill on the right. Jimi's answer: plain text; the newest one gets a tiny still Möbius
 * ([mark]) so it is clear where the talk ends, without an icon on every line (D-069).
 */
@Composable
fun Bubble(text: String, mine: Boolean, mark: Boolean = false) {
    if (text.isBlank()) return
    if (!mine) {
        Column(Modifier.fillMaxWidth().padding(end = 12.dp)) {
            if (mark) MobiusStrip(OrbMood.IDLE, size = 24.dp, modifier = Modifier.padding(bottom = 6.dp), intro = false, still = true)
            Text(text, style = MaterialTheme.typography.bodyLarge, color = JimiColors.Text)
        }
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = JimiColors.Text,
            modifier = Modifier.widthIn(max = 280.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(JimiColors.Surface2)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/** Rounded text field with the mic / send button. */
@Composable
fun InputPill(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier, onGo: () -> Unit) {
    Row(
        modifier.fillMaxWidth()
            .background(JimiColors.Surface2.copy(alpha = 0.85f), RoundedCornerShape(28.dp))
            .padding(start = 20.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text, onValueChange = onText, singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = PanelColors.Text),
            cursorBrush = SolidColor(PanelColors.Accent),
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
            decorationBox = { inner ->
                if (text.isEmpty()) Text("Type to Jimi", color = JimiColors.TertiaryText)
                inner()
            },
        )
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(PanelColors.Accent).clickable(onClick = onGo),
            contentAlignment = Alignment.Center,
        ) {
            if (text.isBlank()) Icon(painterResource(R.drawable.ic_mic), contentDescription = "Speak", tint = JimiColors.OnAccent, modifier = Modifier.size(22.dp))
            else Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = JimiColors.OnAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun Pill(text: String, selected: Boolean = false, onClick: () -> Unit) = Chip(text, selected, onClick = onClick)

@Composable
private fun HistoryTab(vm: AppViewModel) {
    val turns by vm.turnList.collectAsStateWithLifecycle()
    TabList {
        item { PageTitle("History") }
        if (turns.isEmpty()) item { EmptyState("No talks yet", "Say \"Hey Jimi\" to start.") }
        else item { ListCard(null, turns.take(100), "") { TurnRow(it, vm) } }
    }
}

/** One talk: what you said, Jimi's answer under it. Tap for play / right / wrong / fix. */
@Composable
private fun TurnRow(t: ConversationTurn, vm: AppViewModel) {
    var fixing by remember { mutableStateOf(false) }
    var fix by remember { mutableStateOf(t.heard) }
    var wrong by remember { mutableStateOf(false) }
    OpenRow(t.heard.ifBlank { "…" }, t.reply.ifBlank { null }, shortTime(t.timeMs)) {
        Meta("${time(t.timeMs)} · ${t.source}${t.tool?.let { " · $it" } ?: ""}")
        if (t.rawText.isNotBlank() && t.rawText != t.heard) Hint("Heard: ${t.rawText}")
        if (t.textOk == false) Hint("Fixed to: ${t.correction ?: ""}")
        ChipRow {
            t.audioPath?.let { p -> Chip("Play") { vm.play(p) } }
            Chip("Right", selected = t.textOk == true) { vm.rate(t.id, true) }
            Chip(if (wrong) "Saved" else "Wrong", enabled = !wrong) { wrong = true; vm.wrongAnswer(t) }
            Chip("Fix text", selected = fixing) { fixing = !fixing }
        }
        if (fixing) {
            OutlinedTextField(fix, { fix = it }, Modifier.fillMaxWidth(), label = { Text("What you said") })
            Button(onClick = { fixing = false; vm.fix(t.id, fix.trim()) }, enabled = fix.isNotBlank()) { Text("Fix and run") }
        }
    }
}

@Composable
private fun AlertsTab(vm: AppViewModel) {
    val list by vm.notificationList.collectAsStateWithLifecycle()
    val rules by vm.rules.collectAsStateWithLifecycle()
    val ruleOf = rules.associate { it.pkg to it.mode }
    // same app + same title shown once, with a count (D-068)
    val groups = list.groupBy { Triple(it.pkg, it.title, it.text) }.values.toList()
    val (important, other) = groups.partition { isImportant(it.first()) }
    TabList {
        item { PageTitle("Alerts") }
        if (list.isEmpty()) item { EmptyState("No alerts yet", "New notifications show up here.") }
        else {
            item { ListCard("Important", important.take(50), "Nothing important.") { NotificationRow(it, ruleOf[it.first().pkg], vm) } }
            item { ListCard("Other", other.take(50), "Nothing else.") { NotificationRow(it, ruleOf[it.first().pkg], vm) } }
        }
    }
}

private fun isImportant(n: NotificationLog) = n.label?.let { it == "important" } ?: n.important

/** App letter, title, app · time. Tap to see the text and teach Jimi. */
@Composable
private fun NotificationRow(same: List<NotificationLog>, rule: String?, vm: AppViewModel) {
    val n = same.first()
    val important = isImportant(n)
    OpenRow(
        n.title.ifBlank { n.appName },
        n.text.ifBlank { null },
        shortTime(n.timeMs) + if (same.size > 1) "  ×${same.size}" else "",
        lead = { Avatar(n.appName, lit = important) },
    ) {
        Meta("${n.appName} · ${n.status} · ${n.reason}")
        ChipRow {
            Chip("Important", selected = important) { same.forEach { vm.label(it.id, true) } }
            Chip("Noise", selected = !important) { same.forEach { vm.label(it.id, false) } }
            Chip("Always keep app", selected = rule == "keep") { vm.setRule(n.pkg, if (rule == "keep") null else "keep") }
            Chip("Always clear app", selected = rule == "clear") { vm.setRule(n.pkg, if (rule == "clear") null else "clear") }
        }
    }
}

@Composable
private fun NotesTab(vm: AppViewModel) {
    val notes by vm.noteList.collectAsStateWithLifecycle()
    val reminders by vm.reminderList.collectAsStateWithLifecycle()
    val facts by vm.facts.collectAsStateWithLifecycle()
    TabList {
        item { PageTitle("Notes") }
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

@Composable
private fun SettingsTab(vm: AppViewModel, onSetup: () -> Unit, onDeviceCheck: () -> Unit) {
    val spokenWake by vm.spokenWake.collectAsStateWithLifecycle()
    val followUp by vm.followUp.collectAsStateWithLifecycle()
    val panel by vm.showPanel.collectAsStateWithLifecycle()
    val autoClear by vm.autoClear.collectAsStateWithLifecycle()
    val nightExplore by vm.nightExplore.collectAsStateWithLifecycle()
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

    val voice by vm.voice.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableStateOf("") }
    val close = { page = "" }
    val localLabel = mapOf(ChatModels.DEFAULT to "Qwen3 / Gemma", ChatModels.LFM to "LFM2.5 1.2B")

    when (page) {
        "speech" -> SubPage("Speech", close) {
            RowsCard { SpeechEngineSetting(); RowDivider(); SecondPassSetting() }
        }
        "voice" -> SubPage("Voice", close) { VoiceCard(vm) }
        "city" -> SubPage("Home city", close) {
            RowsCard {
                Hint("Used for the weather.")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(cityText, { cityText = it }, Modifier.weight(1f), label = { Text("City") }, singleLine = true)
                    FilledTonalButton(onClick = { vm.setHomeCity(cityText); close() }) { Text("Save") }
                }
            }
        }
        "voiceid" -> SubPage("Voice ID", close) {
            LaunchedEffect(enrollStatus) { vm.refreshVoice() }
            RowsCard {
                ToggleRow("Only my voice when locked", voiceId, "Calls, messages and unlock") { vm.setVoiceId(it) }
            }
            RowsCard("My voice") {
                Hint("$voiceSamples recording(s). Record 3–5 times." + if (enrollStatus.isNotBlank()) " $enrollStatus" else "")
                ChipRow {
                    Chip("Record (8 s)", selected = true, onClick = vm::enrollVoice)
                    Chip("Delete all", onClick = vm::resetVoice)
                }
            }
            RowsCard("Match needed") {
                Hint("%.2f".format(speakerThreshold) + (lastVoiceScore?.let { " · last ${"%.2f".format(it)}" } ?: ""))
                JimiSlider(speakerThreshold, vm::setSpeakerThreshold, 0.2f..0.8f, steps = 11)
            }
            Hint("Say \"panic\" or \"code red\" to lock everything.")
        }
        "pin" -> SubPage("Phone PIN", close) {
            RowsCard {
                Hint(if (pinSet) "Saved, encrypted." else "Not set. Needed for \"unlock my phone\".")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("PIN (empty = remove)") }, singleLine = true)
                    FilledTonalButton(onClick = { vm.setPin(pin); pin = "" }) { Text("Save") }
                }
            }
        }
        "cloud" -> SubPage("Cloud AI", close) {
            RowsCard {
                ToggleRow("Use cloud AI", cloudOn) { vm.setCloudEnabled(it) }
                ToggleRow("Send all chat", cloudChat, "Off = only \"think hard …\"") { vm.setCloudForChat(it) }
                ToggleRow("Share what Jimi remembers", cloudFacts) { vm.setCloudSendFacts(it) }
            }
            RowsCard("Keys") {
                CLOUD_KEY_ROWS.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    KeyRow(row, row.secret in savedKeys, onSave = { vm.setKey(row.secret, row.label, it) }, onTest = { vm.testKey(row.secret) })
                }
                if (message.isNotEmpty()) Hint(message)
            }
            Hint("Tried in order: Groq, Gemini/Claude, OpenRouter, Cloudflare. Phone actions stay on the phone.")
        }
        "local" -> SubPage("Local AI", close) {
            RowsCard {
                localLabel.forEach { (id, label) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { vm.setChatModel(id) }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = chatModel == id, onClick = { vm.setChatModel(id) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            RowsCard {
                Hint(models)
                ChipRow { Chip("Check again", onClick = vm::refreshModels) }
            }
        }
        "assistant" -> SubPage("Assistant mode", close) { AssistantModeCard(vm) }
        "setup" -> SubPage("Setup & data", close) {
            RowsCard {
                NavRow("Setup wizard", onClick = onSetup)
                RowDivider()
                NavRow("Device check", onClick = onDeviceCheck)
                RowDivider()
                NavRow("Set up with root", onClick = vm::rootSetup)
                RowDivider()
                NavRow("Export all data (JSON)", onClick = vm::export)
            }
            if (message.isNotBlank()) Hint(message)
        }
        "apps" -> SubPage("App knowledge", close) { AppKnowledgeCards(vm) }
        "debug" -> SubPage("Run a tool", close) {
            RowsCard {
                OutlinedTextField(tool, { tool = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Tool") }, singleLine = true)
                OutlinedTextField(args, { args = it }, Modifier.fillMaxWidth(), label = { Text("Args JSON") })
                ChipRow { Chip("Run", selected = true) { vm.runTool(tool, args) } }
            }
            Hint(vm.toolNames.joinToString(", "))
        }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PageTitle("Settings")
            RowsCard {
                ToggleRow("Spoken wake reply", spokenWake) { vm.setSpokenWake(it) }
                ToggleRow("Keep listening after answer", followUp) { vm.setFollowUp(it) }
                ToggleRow("Panel on wake", panel) { vm.setShowPanel(it) }
                ToggleRow("Auto-clear noise alerts", autoClear) { vm.setAutoClear(it) }
                ToggleRow("Learn apps at night", nightExplore) { vm.setNightExplore(it) }
            }
            RowsCard {
                NavRow("Voice", Voices.byId(voice).label.substringBefore(" ·")) { page = "voice" }
                RowDivider()
                NavRow("Speech") { page = "speech" }
                RowDivider()
                NavRow("Home city", city.ifBlank { "Not set" }) { page = "city" }
            }
            RowsCard {
                NavRow("Voice ID", if (voiceId) "On" else "Off") { page = "voiceid" }
                RowDivider()
                NavRow("Phone PIN", if (pinSet) "Saved" else "Not set") { page = "pin" }
            }
            RowsCard {
                NavRow("Cloud AI", if (cloudOn) "On" else "Off") { page = "cloud" }
                RowDivider()
                NavRow("Local AI", localLabel[chatModel]) { page = "local" }
                RowDivider()
                NavRow("Assistant mode") { page = "assistant" }
            }
            RowsCard {
                NavRow("App knowledge") { page = "apps" }
                RowDivider()
                NavRow("Setup & data") { page = "setup" }
                RowDivider()
                NavRow("Run a tool") { page = "debug" }
            }
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

    RowsCard {
        ItemRow(Voices.byId(current).label, "Speed ${"%.2f".format(speed)}x") {
            Chip(if (open) "Hide voices" else "Change", selected = open) { open = !open }
        }
        JimiSlider(speed, vm::setVoiceSpeed, 0.8f..1.3f, steps = 9)
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
    var editing by remember { mutableStateOf(false) }
    ItemRow(spec.label, if (saved) "Saved" else "Not saved") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (spec.testable && saved && !editing) Chip("Test", onClick = onTest)
            Chip(if (editing) "Cancel" else if (saved) "Change" else "Add", selected = !saved && !editing) { editing = !editing }
        }
    }
    if (editing) Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value, { value = it.trim() }, Modifier.weight(1f), placeholder = { Text(spec.hint) }, singleLine = true,
            visualTransformation = if (spec.hidden) PasswordVisualTransformation() else VisualTransformation.None,
        )
        Chip(if (value.isEmpty() && saved) "Remove" else "Save", selected = true) { onSave(value); value = ""; editing = false }
    }
}

/** Tasks taught by hand ("learn order coffee" … "done", §H.6): rename or delete. */
@Composable
private fun TaughtCard(vm: AppViewModel) {
    val taught by vm.taught.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadTaught() }
    if (taught.isEmpty()) return
    var editing by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    RowsCard(title = "Learned tasks") {
        taught.forEachIndexed { i, r ->
            if (i > 0) RowDivider()
            if (editing == r.id) {
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth(), singleLine = true)
                Row {
                    Chip("Save") { vm.renameTaught(r, draft); editing = null }
                    Spacer(Modifier.width(6.dp))
                    Chip("Cancel") { editing = null }
                }
            } else {
                val steps = r.stepsJson.split("\"do\"").size - 1
                ItemRow(r.name, "say \"${r.say.substringBefore('\n')}\" · $steps steps") {
                    Chip("Rename") { draft = r.name; editing = r.id }
                    Spacer(Modifier.width(6.dp))
                    Chip("Delete") { vm.deleteTaught(r.id) }
                }
            }
        }
    }
}

/** What Jimi knows per app, what it can't do, and its questions waiting for a yes/no (ROADMAP §J.7). */
@Composable
private fun AppKnowledgeCards(vm: AppViewModel) {
    val apps by vm.appKnowledge.collectAsStateWithLifecycle()
    val asks by vm.pendingAsks.collectAsStateWithLifecycle()
    val names = apps.associate { it.pkg to it.label }
    if (asks.isNotEmpty()) RowsCard(title = "Questions") {
        asks.take(20).forEachIndexed { i, a ->
            if (i > 0) RowDivider()
            ItemRow("Tap \"${a.label}\"?", "${names[a.pkg] ?: a.pkg} · ${AppAskQueue.pretty(a.screen)}") {
                Chip("Yes") { vm.answerAsk(a.id, true) }
                Spacer(Modifier.width(6.dp))
                Chip("No") { vm.answerAsk(a.id, false) }
            }
        }
    }
    TaughtCard(vm)
    if (apps.isEmpty()) {
        EmptyState("No apps yet", "Say \"learn WhatsApp\" and I'll map it.")
        return
    }
    RowsCard(title = "Apps") {
        apps.forEachIndexed { i, a ->
            if (i > 0) RowDivider()
            val meta = buildList {
                add("${a.screens} screens")
                if (a.tasks.isNotEmpty()) add("can: ${a.tasks.joinToString(", ")}")
                if (a.cannot.isNotEmpty()) add("can't: ${a.cannot.joinToString(", ")}")
                if (a.lastLearned > 0) add(timeFmt.format(Date(a.lastLearned))) else add("check again")
            }.joinToString(" · ")
            ItemRow(a.label, meta)
        }
    }
}

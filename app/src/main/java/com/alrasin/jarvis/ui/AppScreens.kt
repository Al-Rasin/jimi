package com.alrasin.jarvis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.R
import com.alrasin.jarvis.brain.Persona
import com.alrasin.jarvis.data.ConversationTurn
import com.alrasin.jarvis.data.NotificationLog
import com.alrasin.jarvis.service.JarvisCoreService
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

internal val timeFmt = SimpleDateFormat("d MMM HH:mm", Locale.US)
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
                    Text("Hi ${Persona.ADDRESS}", Modifier.padding(top = 20.dp), style = MaterialTheme.typography.bodyLarge, color = JimiColors.SecondaryText)
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

package com.alrasin.jarvis.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.agent.appmap.AppAskQueue
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.llm.ChatModels
import com.alrasin.jarvis.voice.Engine
import com.alrasin.jarvis.voice.Voice
import com.alrasin.jarvis.voice.VoicePack
import com.alrasin.jarvis.voice.Voices
import java.util.Date

@Composable
internal fun SettingsTab(vm: AppViewModel, onSetup: () -> Unit, onDeviceCheck: () -> Unit) {
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
    val findWordSet by vm.findWordSet.collectAsStateWithLifecycle()
    val findNumbers by vm.findNumbers.collectAsStateWithLifecycle()
    var findWord by remember { mutableStateOf("") }
    var findList by remember(findNumbers) { mutableStateOf(findNumbers) }
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
        "find" -> SubPage("Find my phone", close) {
            RowsCard {
                Hint("SMS the secret word from a trusted number: phone rings loud, flashes and replies with its location.")
                OutlinedTextField(findWord, { findWord = it }, Modifier.fillMaxWidth(),
                    label = { Text(if (findWordSet) "Secret word (saved; empty = keep)" else "Secret word (4+ letters)") }, singleLine = true)
                OutlinedTextField(findList, { findList = it }, Modifier.fillMaxWidth(), label = { Text("Trusted numbers, comma separated") })
                FilledTonalButton(onClick = { vm.setFindPhone(findWord, findList); findWord = "" }) { Text("Save") }
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
                RowDivider()
                NavRow("Find my phone", if (findWordSet && findNumbers.isNotBlank()) "On" else "Off") { page = "find" }
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
    val onlineVoice by vm.onlineVoice.collectAsStateWithLifecycle()
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
        ToggleRow("Online voice", onlineVoice, "More natural with internet; sends spoken text to Google") { vm.setOnlineVoice(it) }
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

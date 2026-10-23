package com.alrasin.jarvis.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alrasin.jarvis.actions.Outcome
import com.alrasin.jarvis.actions.RootSetup
import com.alrasin.jarvis.actions.ToolExecutor
import com.alrasin.jarvis.actions.ToolRegistry
import com.alrasin.jarvis.brain.ModelBrain
import com.alrasin.jarvis.llm.ChatModels
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Json
import com.alrasin.jarvis.core.Secrets
import com.alrasin.jarvis.core.ToolCall
import com.alrasin.jarvis.core.ToolContext
import com.alrasin.jarvis.data.AppRule
import com.alrasin.jarvis.data.Backup
import com.alrasin.jarvis.data.MemoryDao
import com.alrasin.jarvis.data.NoteDao
import com.alrasin.jarvis.data.NotificationDao
import com.alrasin.jarvis.data.ReminderDao
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.data.AssistantMode
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.voice.PortableModels
import com.alrasin.jarvis.data.TurnDao
import com.alrasin.jarvis.actions.ReminderScheduler
import com.alrasin.jarvis.service.JarvisCoreService
import com.alrasin.jarvis.voice.SpeakerVerifier
import com.alrasin.jarvis.voice.TextToSpeech
import com.alrasin.jarvis.voice.Voice
import com.alrasin.jarvis.voice.VoicePack
import com.alrasin.jarvis.voice.Voices
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Data for the Chat / History / Notifications / Notes / Settings tabs. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val app: Application,
    private val settings: Settings,
    private val turns: TurnDao,
    private val notes: NoteDao,
    private val reminders: ReminderDao,
    private val scheduler: ReminderScheduler,
    private val notifications: NotificationDao,
    private val memory: MemoryDao,
    private val secretsProvider: dagger.Lazy<Secrets>,
    private val backup: Backup,
    private val registry: ToolRegistry,
    private val executor: ToolExecutor,
    private val models: ModelBrain,
    private val speakerProvider: dagger.Lazy<SpeakerVerifier>,
) : AndroidViewModel(app) {
    private val secrets get() = secretsProvider.get()
    private val speaker get() = speakerProvider.get()

    private fun <T> Flow<T>.state(initial: T) = stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)

    val turnList = turns.recent().state(emptyList())
    val noteList = notes.all().state(emptyList())
    val reminderList = reminders.all().state(emptyList())
    val notificationList = notifications.recent().state(emptyList())
    val rules = notifications.rulesFlow().state(emptyList())
    val facts = memory.flow().state(emptyList())
    val spokenWake = settings.spokenWake.state(true)
    val followUp = settings.followUp.state(true)
    val showPanel = settings.showPanel.state(true)
    val autoClear = settings.autoClearNotifications.state(false)
    val voiceId = settings.voiceId.state(false)
    val speakerThreshold = settings.speakerThreshold.state(Settings.DEFAULT_SPEAKER_THRESHOLD)
    val enrollStatus = JarvisCoreService.enrollStatus
    val lastVoiceScore = JarvisCoreService.lastVoiceScore
    val voiceSamples = MutableStateFlow(0)
    val cloudEnabled = settings.cloudEnabled.state(false)
    val cloudForChat = settings.cloudForChat.state(false)
    val cloudSendFacts = settings.cloudSendFacts.state(false)
    val cloudKeySet = MutableStateFlow(false)
    val homeCity = settings.homeCity.state("Dhaka")
    val voice = settings.voice.state("piper-amy")
    val voiceSpeed = settings.voiceSpeed.state(1.10f)
    val setupComplete = settings.setupComplete.map<Boolean, Boolean?> { it }.state(null)
    val assistantMode = settings.assistantMode.state(AssistantMode.BESIDE_GEMINI)
    val deviceFeatures = MutableStateFlow<List<DeviceFeature>>(emptyList())
    val deviceCheckRevision = MutableStateFlow(0)
    val rootAvailable = MutableStateFlow<Boolean?>(null)
    val portableModels = MutableStateFlow<List<com.alrasin.jarvis.voice.ModelStatus>>(emptyList())
    val modelDownloads = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun finishSetup() = viewModelScope.launch { settings.setSetupComplete(true) }
    suspend fun resumeListening() = withContext(Dispatchers.IO) {
        if (!settings.setupComplete.first() || !settings.listeningEnabled.first() || JarvisCoreService.running.value) return@withContext
        val ready = SetupPolicy.wakeReady(
            DeviceCheck.hasPermission(app, android.Manifest.permission.RECORD_AUDIO),
            PortableModels.status(app).associate { it.id to it.ready },
        )
        if (!ready) return@withContext
        JarvisCoreService.start(app)
        withTimeoutOrNull(30_000) { JarvisCoreService.running.first { it } }
    }
    fun setAssistantMode(mode: AssistantMode) = viewModelScope.launch { settings.setAssistantMode(mode) }
    fun refreshDeviceCheck() = viewModelScope.launch(Dispatchers.IO) {
        deviceFeatures.value = DeviceCheck.features(app)
        deviceCheckRevision.value++
        portableModels.value = PortableModels.status(app)
        installedPacks.value = installedNow()
    }
    fun checkRoot() = viewModelScope.launch(Dispatchers.IO) { rootAvailable.value = RootShell.available }
    fun downloadModel(id: String) {
        if (modelDownloads.value.isNotEmpty()) return
        modelDownloads.value = mapOf(id to 0f)
        viewModelScope.launch {
            runCatching { PortableModels.download(app, id) { modelDownloads.value = mapOf(id to it) } }
                .onFailure { _message.value = "Model download failed: ${it.message}" }
            modelDownloads.value = emptyMap()
            refreshDeviceCheck()
        }
    }
    fun importWake(uri: android.net.Uri) = viewModelScope.launch {
        runCatching { PortableModels.importWake(app, uri) }
            .onSuccess { _message.value = "Hey Jimi model imported." }
            .onFailure { _message.value = "Import failed: ${it.message}" }
        refreshDeviceCheck()
    }
    fun importLlm(uri: android.net.Uri) = viewModelScope.launch {
        if (modelDownloads.value.isNotEmpty()) return@launch
        modelDownloads.value = mapOf("llm" to 0f)
        runCatching { PortableModels.importLlm(app, uri) }
            .onSuccess { _message.value = "Local AI model imported." }
            .onFailure { _message.value = "Import failed: ${it.message}" }
        modelDownloads.value = emptyMap()
        refreshDeviceCheck()
        refreshModels()
    }
    val chatModel = settings.chatModel.state(ChatModels.DEFAULT)
    /** Ids of downloaded voice packs. */
    val installedPacks = MutableStateFlow<Set<String>>(emptySet())
    /** Pack id → download progress 0..1. */
    val downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Id of the voice being tried, null when idle. */
    val trying = MutableStateFlow<String?>(null)
    val toolNames: List<String> get() = registry.all.map { it.name }.sorted()

    private val _message = MutableStateFlow("")
    /** Result line of the last button (setup, export, tool run). */
    val message: StateFlow<String> = _message
    val pinSet = MutableStateFlow(false)
    val modelStatus = MutableStateFlow("Checking models…")
    private var player: MediaPlayer? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            voiceSamples.value = speaker.samples
            cloudKeySet.value = secrets.has(Secrets.CLOUD_KEY)
            pinSet.value = secrets.has(Secrets.PIN)
        }
        refreshDeviceCheck()
        refreshModels()
    }

    fun ask(text: String) = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.ask(app, text) }

    fun listen() = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.listen(app) }

    fun rate(id: Long, ok: Boolean) = viewModelScope.launch { turns.rate(id, ok, null) }

    fun fix(id: Long, text: String) = viewModelScope.launch {
        turns.rate(id, false, text)
        JarvisCoreService.ask(app, text, source = "fix")
    }

    fun play(path: String) {
        player?.release()
        player = runCatching { MediaPlayer().apply {
            setOnPreparedListener { it.start() }
            setDataSource(path)
            prepareAsync()
        } }
            .onFailure { JarvisLog.e("play failed", it) }.getOrNull()
    }

    fun label(id: Long, important: Boolean) = viewModelScope.launch {
        notifications.setLabel(id, if (important) "important" else "noise")
    }

    /** [mode]: keep | clear | null (no rule). */
    fun setRule(pkg: String, mode: String?) = viewModelScope.launch {
        if (mode == null) notifications.clearRule(pkg) else notifications.setRule(AppRule(pkg, mode))
    }

    fun deleteNote(id: Long) = viewModelScope.launch { notes.delete(id) }

    fun deleteReminder(id: Long) = viewModelScope.launch(Dispatchers.IO) {
        scheduler.cancel(id)
        reminders.delete(id)
    }

    fun forget(id: Long) = viewModelScope.launch { memory.delete(id) }

    fun setSpokenWake(v: Boolean) = viewModelScope.launch { settings.setSpokenWake(v) }
    fun setFollowUp(v: Boolean) = viewModelScope.launch { settings.setFollowUp(v) }
    fun setShowPanel(v: Boolean) = viewModelScope.launch { settings.setShowPanel(v) }
    fun setAutoClear(v: Boolean) = viewModelScope.launch { settings.setAutoClearNotifications(v) }
    fun setVoiceId(v: Boolean) = viewModelScope.launch { settings.setVoiceId(v) }
    fun setSpeakerThreshold(v: Float) = viewModelScope.launch { settings.setSpeakerThreshold(v) }
    fun setCloudEnabled(v: Boolean) = viewModelScope.launch { settings.setCloudEnabled(v) }
    fun setCloudForChat(v: Boolean) = viewModelScope.launch { settings.setCloudForChat(v) }
    fun setCloudSendFacts(v: Boolean) = viewModelScope.launch { settings.setCloudSendFacts(v) }

    private fun installedNow() = Voices.ALL.map { it.pack }.distinct().filter { Voices.installed(app, it) }.map { it.id }.toSet()

    fun downloadVoice(pack: VoicePack) {
        if (pack.id in downloads.value) return
        downloads.value += pack.id to 0f
        viewModelScope.launch {
            runCatching { Voices.download(app, pack) { p -> downloads.value += pack.id to p } }
                .onFailure { JarvisLog.e("voice download failed", it); _message.value = "Download failed. Check internet and try again." }
            downloads.value -= pack.id
            installedPacks.value = withContext(Dispatchers.IO) { installedNow() }
        }
    }

    fun deleteVoice(pack: VoicePack) = viewModelScope.launch(Dispatchers.IO) {
        if (Voices.byId(voice.value).pack.id == pack.id) setVoice(Voices.BUILT_IN)
        Voices.delete(app, pack)
        installedPacks.value = installedNow()
    }

    fun setVoice(id: String) = viewModelScope.launch { settings.setVoice(id) }
    fun setVoiceSpeed(v: Float) = viewModelScope.launch { settings.setVoiceSpeed(v) }
    fun setChatModel(v: String) = viewModelScope.launch { settings.setChatModel(v) }

    /** Loads [v] just for a sample sentence (a few seconds the first time), then frees it. */
    fun tryVoice(v: Voice) {
        if (trying.value != null) return
        trying.value = v.id
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { TextToSpeech(app, v, speed = voiceSpeed.value).use { it.speak("Hi Boss, I'm Jimi. How can I help?") } }
                .onFailure { JarvisLog.e("try voice failed", it); _message.value = "Voice preview failed. Delete its download, then download it again." }
            trying.value = null
        }
    }

    fun setCloudKey(key: String) = viewModelScope.launch(Dispatchers.IO) {
        secrets.put(Secrets.CLOUD_KEY, key.trim().ifEmpty { null })
        cloudKeySet.value = secrets.has(Secrets.CLOUD_KEY)
        _message.value = if (cloudKeySet.value) "API key saved (encrypted)." else "API key removed."
    }

    fun enrollVoice() = viewModelScope.launch(Dispatchers.IO) { JarvisCoreService.enroll(app) }
    fun refreshVoice() = viewModelScope.launch(Dispatchers.IO) { voiceSamples.value = speaker.samples }

    fun resetVoice() = viewModelScope.launch(Dispatchers.IO) {
        speaker.reset()
        voiceSamples.value = 0
        _message.value = "Voice ID recordings deleted."
    }
    fun setHomeCity(v: String) = viewModelScope.launch { settings.setHomeCity(v.trim().ifEmpty { "Dhaka" }) }

    fun setPin(pin: String) = viewModelScope.launch(Dispatchers.IO) {
        secrets.put(Secrets.PIN, pin.trim().ifEmpty { null })
        pinSet.value = secrets.has(Secrets.PIN)
        _message.value = if (pinSet.value) "PIN saved (encrypted)." else "PIN removed."
    }

    fun rootSetup() = viewModelScope.launch(Dispatchers.IO) {
        _message.value = "Setting up…"
        val lines = RootSetup.run(app)
        val failed = lines.filter { it.startsWith("fail") }
        _message.value = if (failed.isEmpty()) "Setup done (${lines.size} steps)." else failed.joinToString("\n")
    }

    fun export() = viewModelScope.launch(Dispatchers.IO) {
        _message.value = runCatching { "Saved: ${backup.export().path}" }.getOrElse { "Export failed: ${it.message}" }
    }

    fun refreshModels() {
        viewModelScope.launch(Dispatchers.IO) { modelStatus.value = models.status(settings.chatModel.first()) }
    }

    /** Debug: run any tool with JSON args, no voice. Risky tools still ask (confirmed = false). */
    fun runTool(name: String, argsJson: String) = viewModelScope.launch(Dispatchers.IO) {
        val args = runCatching {
            (Json.parse(argsJson.ifBlank { "{}" }) as Map<*, *>).entries.associate { (k, v) -> k.toString() to v.toString() }
        }.getOrElse { _message.value = "Bad JSON: ${it.message}"; return@launch }
        val o = executor.execute(ToolCall(name, args), ToolContext(app, "debug", false), confirmed = true, voiceOk = true)
        _message.value = when (o) {
            is Outcome.Done -> (if (o.result.ok) "ok: " else "fail: ") + o.result.say + o.result.data.take(500).let { if (it.isEmpty()) "" else "\n$it" }
            is Outcome.NeedConfirm -> "asks: ${o.question}"
            is Outcome.Denied -> "denied: ${o.why}"
        }
    }
}

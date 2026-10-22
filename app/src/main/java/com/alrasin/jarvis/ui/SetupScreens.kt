package com.alrasin.jarvis.ui

import android.Manifest
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.alrasin.jarvis.data.AssistantMode
import com.alrasin.jarvis.service.JarvisCoreService

@Composable
private fun RefreshOnResume(vm: AppViewModel) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, vm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.refreshDeviceCheck()
        }
        owner.lifecycle.addObserver(observer)
        vm.refreshDeviceCheck()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
internal fun AssistantModeCard(vm: AppViewModel) {
    val mode by vm.assistantMode.collectAsStateWithLifecycle()
    val features by vm.deviceFeatures.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var settingsUnavailable by remember { mutableStateOf(false) }
    RefreshOnResume(vm)
    GlassCard(title = "Assistant mode") {
        AssistantMode.entries.forEach { option ->
            OutlinedButton(onClick = { vm.setAssistantMode(option) }, modifier = Modifier.fillMaxWidth()) {
                Text((if (mode == option) "● " else "○ ") + option.label)
            }
        }
        Text(if (mode == AssistantMode.REPLACE_GEMINI)
            "Choose Jimi in Default digital assistant settings. The assistant gesture or long-press home/power then opens Jimi where supported by your phone. Hey Google may stop."
        else "Keep Google/Gemini as your default assistant for Hey Google. Jimi listens only for Hey Jimi in its foreground service. Changing this mode does not change your system default.",
            style = MaterialTheme.typography.bodySmall)
        Text("Android may give only one app usable microphone capture at a time. Jimi pauses when another assistant or call takes the mic and retries when it becomes available. Both wake words are not guaranteed to listen simultaneously.",
            style = MaterialTheme.typography.bodySmall)
        Text(if (features.firstOrNull { it.id == "assistant" }?.ready == true)
            "System default: Jimi. ${if (mode == AssistantMode.BESIDE_GEMINI) "Select Google below to keep Gemini as default." else "Ready."}"
        else "System default: another app or none.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { settingsUnavailable = !DeviceCheck.open(context, Settings.ACTION_VOICE_INPUT_SETTINGS) }) {
            Text("Default digital assistant settings")
        }
        if (settingsUnavailable) Text("This phone did not expose that settings screen. Search Android Settings for ‘Default digital assistant app’.")
    }
}

@Composable
fun SetupWizard(vm: AppViewModel, home: HomeViewModel, onFinish: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val titles = listOf("Choose assistant mode", "Allow permissions", "Allow background access", "Install offline models", "Choose a voice", "Test Hey Jimi")
    val message by vm.message.collectAsStateWithLifecycle()
    RefreshOnResume(vm)
    BackHandler(step > 0) { step-- }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GradientTitle("Set up Jimi", 28)
        Text("${step + 1} of ${titles.size} · ${titles[step]}")
        LinearProgressIndicator(progress = { (step + 1f) / titles.size }, modifier = Modifier.fillMaxWidth())
        when (step) {
            0 -> AssistantModeCard(vm)
            1 -> RuntimePermissionCard(vm)
            2 -> SpecialAccessCard(vm)
            3 -> OfflineModelsCard(vm)
            4 -> VoiceCard(vm)
            5 -> WakeTestCard(vm, home)
        }
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        Text("You can skip optional access or finish later; unavailable features stay disabled by Android. Re-run this wizard from Settings.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step > 0) OutlinedButton(onClick = { step-- }) { Text("Back") }
            if (step < titles.lastIndex) Button(onClick = { step++ }) { Text("Continue") }
            else Button(onClick = onFinish) { Text("Finish setup") }
        }
        TextButton(onClick = onFinish) { Text("Finish later") }
    }
}

@Composable
private fun RuntimePermissionCard(vm: AppViewModel) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    val permissions = remember { SetupPolicy.runtimePermissions(Build.VERSION.SDK_INT) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        revision++
        vm.refreshDeviceCheck()
    }
    val features by vm.deviceFeatures.collectAsStateWithLifecycle()
    val checkRevision by vm.deviceCheckRevision.collectAsStateWithLifecycle()
    GlassCard(title = "App permissions") {
        Text("Microphone is needed for voice. The other permissions enable the listed features and can be skipped.")
        permissions.forEach { (permission, label) ->
            val granted = remember(permission, revision, features, checkRevision) { DeviceCheck.hasPermission(context, permission) }
            Text("${if (granted) "✓" else "○"} $label", style = MaterialTheme.typography.bodySmall)
            if (!granted) TextButton(onClick = {
                val requested = if (permission == Manifest.permission.ACCESS_FINE_LOCATION)
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, permission) else arrayOf(permission)
                launcher.launch(requested)
            }) { Text("Allow") }
        }
        TextButton(onClick = { DeviceCheck.open(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) {
            Text("App settings (if permission was denied)")
        }
    }
}

@Composable
private fun SpecialAccessCard(vm: AppViewModel) {
    val context = LocalContext.current
    val features by vm.deviceFeatures.collectAsStateWithLifecycle()
    val root by vm.rootAvailable.collectAsStateWithLifecycle()
    var unavailable by remember { mutableStateOf(false) }
    GlassCard(title = "Device access") {
        Text("Root: ${when (root) { true -> "available"; false -> "unavailable — normal Android features still work"; null -> "not checked (optional)" }}")
        OutlinedButton(onClick = { vm.checkRoot() }) { Text("Check root (may ask for permission)") }
        features.filter { it.id != "mic" }.forEach { feature ->
            Text("${if (feature.ready) "✓" else "○"} ${feature.label}")
            Text(feature.detail, style = MaterialTheme.typography.bodySmall)
            feature.action?.let { action ->
                TextButton(onClick = { unavailable = !DeviceCheck.open(context, action, feature.packageUri) }) { Text("Open settings") }
            }
        }
        if (unavailable) Text("This settings shortcut is unavailable. Use Android Settings to enable the access shown above.")
        Text("If your phone limits background apps, also allow Jimi in its battery/autostart settings. Root-only actions such as silent mobile-data changes and PIN unlock remain unavailable without root.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = vm::refreshDeviceCheck) { Text("Check again") }
    }
}

@Composable
private fun OfflineModelsCard(vm: AppViewModel) {
    val models by vm.portableModels.collectAsStateWithLifecycle()
    val downloads by vm.modelDownloads.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importWake) }
    val llmPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importLlm) }
    GlassCard(title = "Offline models") {
        Text("Download once with internet, then use offline. Models are stored in this app's storage on this phone.")
        models.forEach { model ->
            Text("${if (model.ready) "✓" else "○"} ${model.label} · ${model.sizeLabel}")
            Text(model.detail, style = MaterialTheme.typography.bodySmall)
            val progress = downloads[model.id]
            if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            if (!model.ready && model.downloadable) {
                OutlinedButton(onClick = { vm.downloadModel(model.id) }, enabled = downloads.isEmpty()) { Text("Download ${model.label}") }
            }
            if (model.id == "wake_jimi" && !model.ready) {
                OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = downloads.isEmpty()) { Text("Select Hey Jimi .onnx model") }
            }
            if (model.id == "llm") {
                OutlinedButton(onClick = { llmPicker.launch(arrayOf("*/*")) }, enabled = downloads.isEmpty()) { Text("Select local AI .gguf model") }
                if (progress != null) Text("Importing model…", style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = vm::refreshDeviceCheck) { Text("Check models again") }
    }
}

@Composable
private fun WakeTestCard(vm: AppViewModel, home: HomeViewModel) {
    val features by vm.deviceFeatures.collectAsStateWithLifecycle()
    val models by vm.portableModels.collectAsStateWithLifecycle()
    val running by home.running.collectAsStateWithLifecycle()
    val phase by home.phase.collectAsStateWithLifecycle()
    val micAvailable by JarvisCoreService.microphoneAvailable.collectAsStateWithLifecycle()
    val events by home.events.collectAsStateWithLifecycle()
    var startedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val ready = SetupPolicy.wakeReady(features.firstOrNull { it.id == "mic" }?.ready == true, models.associate { it.id to it.ready })
    val detected = SetupPolicy.newWakeDetected(startedAt, events.map { it.timeMs })
    GlassCard(title = "Say Hey Jimi") {
        Text("Tap Start, then say ‘Hey Jimi, what time is it?’ Wait for Jimi's panel and spoken reply.")
        if (!ready) Text("Allow microphone and install wake-word and speech models first.")
        if (models.firstOrNull { it.id == "tts" }?.ready != true) {
            Text("The built-in voice model is missing. Install it or select a downloaded voice to hear spoken replies.")
        }
        Button(onClick = { startedAt = System.currentTimeMillis(); home.start() }, enabled = ready) { Text("Start wake test") }
        if (startedAt != null) Text(when {
            detected -> "✓ Hey Jimi was detected. Check that the reply sounds right."
            !running -> "Starting microphone… If it does not start, check permissions and models."
            !micAvailable -> "Microphone unavailable or used by another app. Jimi will retry when it is free."
            else -> "Service: $phase. Waiting for a new Hey Jimi detection…"
        })
        if (running) OutlinedButton(onClick = home::stop) { Text("Stop listening") }
        Text("Listening continues after setup until you turn it off. If another app holds the microphone, finish that app's capture and try again.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DeviceCheckScreen(vm: AppViewModel, onBack: () -> Unit) {
    RefreshOnResume(vm)
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back to Settings") }
        GradientTitle("Device check", 28)
        Text("Available features depend on Android, hardware and permissions. Missing access is shown below.")
        Text("This build requires Android 11 or newer. Local AI native libraries currently target ARM64; unsupported hardware needs a compatible build. Device: Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.joinToString()}.", style = MaterialTheme.typography.bodySmall)
        RuntimePermissionCard(vm)
        SpecialAccessCard(vm)
        OfflineModelsCard(vm)
    }
}

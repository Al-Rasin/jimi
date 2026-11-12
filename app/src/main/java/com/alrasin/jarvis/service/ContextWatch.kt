package com.alrasin.jarvis.service

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.brain.ContextModes
import com.alrasin.jarvis.core.ContextMode
import com.alrasin.jarvis.core.ContextNow
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.data.Settings
import com.alrasin.jarvis.voice.TextToSpeech
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

/**
 * Watches audio outputs (car Bluetooth, headphones) and the clock, and keeps [ContextNow.mode] current
 * (ROADMAP B §I.11). Bedtime turns Do Not Disturb on and lowers Jimi's voice; leaving bedtime undoes both.
 */
class ContextWatch(private val context: Context, private val scope: CoroutineScope, private val settings: Settings) {
    private val audio = context.getSystemService(AudioManager::class.java)
    @Volatile private var prefs = ContextModes.Prefs()
    private var job: Job? = null
    /** True when Jimi turned DND on for bedtime, so only then it turns it off again. */
    private var dndByUs = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { check() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { check() }
    }

    fun start() {
        audio.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        job = scope.launch {
            launch {
                combine(settings.carMode, settings.headphonesMode, settings.bedtimeMode, settings.bedtimeHours) { c, h, b, hrs ->
                    ContextModes.Prefs(c, h, b, hrs)
                }.collect { prefs = it; check() }
            }
            // bedtime starts and ends by the clock
            while (isActive) { delay(60_000); check() }
        }
    }

    fun stop() {
        runCatching { audio.unregisterAudioDeviceCallback(devices) }
        job?.cancel()
        // the scope is ending, so undo bedtime without root (no coroutine)
        TextToSpeech.gain = 1f
        if (dndByUs) { dndByUs = false; dndApi(context.getSystemService(NotificationManager::class.java), false) }
        ContextNow.mode = ContextMode()
    }

    private fun check() {
        scope.launch(Dispatchers.IO) {
            val now = LocalTime.now()
            val next = ContextModes.decide(outputs(), now.hour * 60 + now.minute, prefs)
            val old = ContextNow.mode
            if (next == old) return@launch
            ContextNow.mode = next
            JarvisLog.i("context mode: ${old.label()} → ${next.label()}")
            if (next.quiet && !old.quiet) enterBedtime()
            if (!next.quiet && old.quiet) leaveBedtime()
        }
    }

    private fun outputs(): List<ContextModes.Output> {
        val cars = carNames()
        return audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { d ->
            val kind = when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> ContextModes.Kind.WIRED
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> ContextModes.Kind.BLUETOOTH
                AudioDeviceInfo.TYPE_USB_HEADSET -> ContextModes.Kind.USB
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_TELEPHONY -> return@mapNotNull null
                else -> ContextModes.Kind.OTHER
            }
            val name = d.productName?.toString().orEmpty()
            ContextModes.Output(kind, name, name.isNotEmpty() && name in cars)
        }
    }

    /** Names of paired Bluetooth devices that say they are car audio or hands-free kits. */
    @SuppressLint("MissingPermission")
    private fun carNames(): Set<String> = runCatching {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptySet()
        adapter.bondedDevices.orEmpty().filter {
            it.bluetoothClass?.deviceClass in setOf(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO, BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
        }.mapNotNull { it.name }.toSet()
    }.getOrDefault(emptySet())

    private suspend fun enterBedtime() {
        TextToSpeech.gain = ContextModes.QUIET_GAIN
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return
        dndByUs = setDnd(nm, true)
    }

    private suspend fun leaveBedtime() {
        TextToSpeech.gain = 1f
        if (!dndByUs) return
        dndByUs = false
        setDnd(context.getSystemService(NotificationManager::class.java), false)
    }

    private suspend fun setDnd(nm: NotificationManager, on: Boolean): Boolean =
        dndApi(nm, on) || RootShell.run("cmd notification set_dnd ${if (on) "priority" else "off"}").ok

    private fun dndApi(nm: NotificationManager, on: Boolean): Boolean = runCatching {
        if (!nm.isNotificationPolicyAccessGranted) false else {
            nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
            true
        }
    }.getOrDefault(false)
}

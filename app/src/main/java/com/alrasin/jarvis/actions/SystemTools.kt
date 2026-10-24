package com.alrasin.jarvis.actions

import android.annotation.SuppressLint
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.MediaScannerConnection
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Environment
import android.os.PowerManager
import android.os.StatFs
import android.provider.Settings
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.Risk
import com.alrasin.jarvis.core.Tool
import com.alrasin.jarvis.core.ToolResult
import com.alrasin.jarvis.core.pollUntil
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Flashlight. Camera service is opened on first use, so a camera problem never breaks the tool list. */
class Torch(private val context: Context) {
    @Volatile private var state: Boolean? = null
    private var usesRoot = false
    private var rootLed: String? = null

    private suspend fun led(): String? {
        rootLed?.let { return it }
        if (!RootShell.available) return null
        val paths = RootShell.run("for l in /sys/class/leds/*; do [ -f \"\$l/brightness\" ] && [ -f \"\$l/max_brightness\" ] && echo \"\$l\"; done")
        return DeviceActionPolicy.torchDirectories(paths.out.lines()).firstOrNull().also { rootLed = it }
    }

    suspend fun readState(): Boolean? {
        runCatching { cm }
        if (!usesRoot) {
            if (state == null) delay(80)
            state?.let { return it }
        }
        val path = led() ?: return null
        return RootShell.run("cat ${RootShell.quote("$path/brightness")}").out.trim().toIntOrNull()?.let { it > 0 }
    }

    private val cm: CameraManager by lazy {
        context.getSystemService(CameraManager::class.java).also { cm ->
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) state = enabled }
                override fun onTorchModeUnavailable(cameraId: String) { if (cameraId == id) state = null }
            }, null)
        }
    }
    private val id: String? by lazy {
        runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
            .getOrNull()
    }

    /** MIUI can accept setTorchMode from a background app and silently ignore it; wait for the callback to confirm. */
    private suspend fun confirmed(value: Boolean): Boolean = pollUntil(10, 50) { state == value }

    /** Camera API first; MIUI refuses it for background apps, then the flash LED is switched with root. */
    suspend fun set(value: Boolean): Boolean {
        val api = runCatching { id?.let { state = null; cm.setTorchMode(it, value); true } ?: false }
            .onFailure { JarvisLog.e("torch: camera API failed", it) }.getOrDefault(false)
        if (api && confirmed(value)) { usesRoot = false; return true }
        if (api) JarvisLog.i("torch: camera API accepted but torch did not change; trying root LED")
        val path = led() ?: return false
        val maximum = DeviceActionPolicy.torchBrightness(
            RootShell.run("cat ${RootShell.quote("$path/max_brightness")}").out,
        ) ?: return false
        val ok = RootShell.run("echo ${if (value) maximum else 0} > ${RootShell.quote("$path/brightness")}").ok
        usesRoot = ok
        return ok
    }
}

val TOGGLE_TARGETS = listOf("flashlight", "wifi", "bluetooth", "data", "dnd", "airplane", "location", "hotspot", "battery_saver", "auto_rotate",
    "wireless_debugging", "usb_debugging")

fun systemTools(context: Context, torch: Torch): List<Tool> {
    fun settingsPage(target: String): Boolean {
        val action = when (target) {
            "wifi" -> Settings.Panel.ACTION_WIFI
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "data" -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
            "dnd" -> Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "battery_saver" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "auto_rotate", "brightness" -> Settings.ACTION_DISPLAY_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "wireless_debugging", "usb_debugging" -> Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
            else -> return false
        }
        return context.launch(Intent(action)) || context.launch(Intent(Settings.ACTION_SETTINGS))
    }

    suspend fun writeSettings(vararg values: Pair<String, Int>): Boolean {
        val written = runCatching {
            Settings.System.canWrite(context) && values.all { (key, value) ->
                Settings.System.putInt(context.contentResolver, key, value)
            }
        }.getOrDefault(false)
        return written || RootShell.run(*values.map { (key, value) -> "settings put system $key $value" }.toTypedArray()).ok
    }
    suspend fun isOn(target: String): Boolean? {
        if (target == "flashlight") return torch.readState()
        return runCatching { when (target) {
        "wifi" -> context.getSystemService(WifiManager::class.java).isWifiEnabled
        "bluetooth" -> context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled
        "data" -> Settings.Global.getInt(context.contentResolver, "mobile_data") == 1
        "dnd" -> context.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
            when (it) {
                NotificationManager.INTERRUPTION_FILTER_ALL -> false
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_NONE,
                NotificationManager.INTERRUPTION_FILTER_ALARMS -> true
                else -> null
            }
        }
        "airplane" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
        "location" -> context.getSystemService(LocationManager::class.java).isLocationEnabled
        "battery_saver" -> context.getSystemService(PowerManager::class.java).isPowerSaveMode
        "auto_rotate" -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
        "wireless_debugging" -> Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
        "usb_debugging" -> Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        else -> null
        } }.getOrNull()
    }

    suspend fun set(target: String, on: Boolean): Boolean {
        val e = if (on) "enable" else "disable"
        return when (target) {
            "flashlight" -> torch.set(on)
            "wifi" -> RootShell.run("svc wifi $e").ok
            "bluetooth" -> bluetoothFallback(context, on) || RootShell.run("svc bluetooth $e").ok
            "data" -> RootShell.run("svc data $e").ok
            "dnd" -> runCatching {
                val notifications = context.getSystemService(NotificationManager::class.java)
                if (!notifications.isNotificationPolicyAccessGranted) false else {
                    notifications.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
                    true
                }
            }.getOrDefault(false) || RootShell.run("cmd notification set_dnd ${if (on) "priority" else "off"}").ok
            "airplane" -> RootShell.run("cmd connectivity airplane-mode $e").ok
            "location" -> RootShell.run(
                "settings put secure location_mode ${if (on) 3 else 0}",
                "cmd location set-location-enabled $on 2>/dev/null; true",
            ).ok
            "hotspot" -> {
                context.launch(Intent("android.settings.TETHER_SETTINGS")) || settingsPage(target)
            }
            "battery_saver" -> RootShell.run("cmd power set-mode ${if (on) 1 else 0}").ok
            "auto_rotate" -> writeSettings(Settings.System.ACCELEROMETER_ROTATION to if (on) 1 else 0)
            // Wireless debugging needs Wi-Fi and USB debugging (developer options) on first.
            "wireless_debugging" -> RootShell.run(
                *(if (on) arrayOf("settings put global development_settings_enabled 1", "settings put global adb_enabled 1", "svc wifi enable") else emptyArray()),
                "settings put global adb_wifi_enabled ${if (on) 1 else 0}",
            ).ok
            "usb_debugging" -> RootShell.run(
                *(if (on) arrayOf("settings put global development_settings_enabled 1") else emptyArray()),
                "settings put global adb_enabled ${if (on) 1 else 0}",
            ).ok
            else -> false
        }
    }

    fun nice(target: String) = when (target) {
        "dnd" -> "Do not disturb"; "data" -> "Mobile data"; "battery_saver" -> "Battery saver"
        "auto_rotate" -> "Auto rotate"; "wifi" -> "Wi-Fi"; "airplane" -> "Airplane mode"
        "wireless_debugging" -> "Wireless debugging"; "usb_debugging" -> "USB debugging"
        else -> target.replaceFirstChar { it.uppercase() }
    }

    val maxBrightness by lazy {
        runCatching {
            Resources.getSystem().getIdentifier("config_screenBrightnessSettingMaximum", "integer", "android")
                .takeIf { it != 0 }?.let { Resources.getSystem().getInteger(it) }
        }.getOrNull() ?: 255
    }

    return listOf(
        SimpleTool(
            "toggle", "Turn a phone feature on or off",
            listOf(arg("target", "feature", choices = TOGGLE_TARGETS), arg("state", "on, off or toggle", choices = listOf("on", "off", "toggle"))),
        ) { c, _ ->
            val target = c.arg("target") ?: return@SimpleTool ToolResult.fail("Turn what on or off?")
            if (target !in TOGGLE_TARGETS) return@SimpleTool ToolResult.fail("I can't switch $target.")
            if (target == "hotspot") {
                return@SimpleTool if (set(target, true)) ToolResult.ok("Boss, I opened the hotspot page. Tap the switch.")
                else ToolResult.fail("Boss, I couldn't open hotspot settings.")
            }
            val before = isOn(target)
            val want = requestedToggleState(c.arg("state"), before)
                ?: return@SimpleTool ToolResult.fail("Boss, I can't read ${nice(target)}. Say on or off.")
            if (before == want) return@SimpleTool toggleTruth(nice(target), want, before)
            val requested = set(target, want)
            if (!requested && settingsPage(target)) return@SimpleTool ToolResult.ok("Boss, I opened ${nice(target)} settings. Set it ${if (want) "on" else "off"} there.")
            var actual = isOn(target)
            // poll often so a quick switch is reported quickly; same 2 s ceiling as before
            if (actual != want) pollUntil(20, 100, delayFirst = true) { actual = isOn(target); actual == want }
            if (actual != want && settingsPage(target)) ToolResult.ok("Boss, the change isn't confirmed. I opened ${nice(target)} settings; set it ${if (want) "on" else "off"} there.")
            else toggleTruth(nice(target), want, actual)
        },
        SimpleTool("set_brightness", "Screen brightness", listOf(arg("level", "0-100, up, down or auto"))) { c, _ ->
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxBrightness / 2)
            val target = when (val l = c.arg("level")) {
                "auto" -> {
                    if (!writeSettings(Settings.System.SCREEN_BRIGHTNESS_MODE to 1) && settingsPage("brightness")) {
                        return@SimpleTool ToolResult.ok("Boss, I opened display settings. Turn on auto brightness there.")
                    }
                    val actual = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 1 }.getOrNull()
                    return@SimpleTool toggleTruth("Auto brightness", true, actual)
                }
                "up" -> cur + maxBrightness / 5
                "down" -> cur - maxBrightness / 5
                else -> (l?.toDoubleOrNull()?.toInt() ?: return@SimpleTool ToolResult.fail("What brightness?")) * maxBrightness / 100
            }.coerceIn(1, maxBrightness)
            if (!writeSettings(Settings.System.SCREEN_BRIGHTNESS_MODE to 0, Settings.System.SCREEN_BRIGHTNESS to target) && settingsPage("brightness")) {
                return@SimpleTool ToolResult.ok("Boss, I opened display settings. Set brightness to ${target * 100 / maxBrightness} percent there.")
            }
            val actual = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
            val manual = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE) == 0 }.getOrDefault(false)
            if (actual == null) ToolResult.fail("Boss, I couldn't verify brightness.")
            else if (actual == target && manual) ToolResult.ok("Boss, brightness is ${actual * 100 / maxBrightness} percent.")
            else ToolResult.fail("Boss, brightness reads ${actual * 100 / maxBrightness} percent. The change isn't confirmed.")
        },
        SimpleTool("device_status", "Time, date, battery, storage or all", listOf(arg("what", "what to tell", choices = listOf("time", "date", "battery", "storage", "all")))) { c, _ ->
            val now = Date()
            val time = SimpleDateFormat("h:mm a", Locale.US).format(now)
            val date = SimpleDateFormat("EEEE, d MMMM", Locale.US).format(now)
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val status = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val battery = "Battery $pct percent${if (charging) ", charging" else ""}."
            val fs = StatFs(Environment.getDataDirectory().path)
            val storage = "${fs.availableBytes / 1_000_000_000} GB free of ${fs.totalBytes / 1_000_000_000} GB."
            ToolResult.ok(
                when (c.arg("what")) {
                    "time" -> "It's $time."
                    "date" -> "Today is $date."
                    "battery" -> battery
                    "storage" -> storage
                    else -> "It's $time, $date. $battery $storage"
                },
            )
        },
        SimpleTool("take_screenshot", "Take a screenshot") { _, _ ->
            if (JarvisAccessibilityService.instance?.global(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) == true) {
                return@SimpleTool ToolResult.ok("Screenshot requested. Android will show it when saved.")
            }
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
            val f = File(dir, "Screenshot_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}_Jarvis.png")
            val r = RootShell.run("mkdir -p ${RootShell.quote(dir.path)}", "screencap -p ${RootShell.quote(f.path)}")
            if (!r.ok || !f.isFile || f.length() == 0L) return@SimpleTool ToolResult.fail("Screenshot failed. Enable accessibility in Setup or use the phone's screenshot buttons.")
            MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null)
            ToolResult.ok("Screenshot saved.")
        },
        SimpleTool("lock_phone", "Lock the phone (screen off)") { _, _ ->
            val a = JarvisAccessibilityService.instance
            val requested = a?.lockScreen() == true || RootShell.run("input keyevent 223").ok
            if (!requested) return@SimpleTool ToolResult.fail("Boss, I couldn't request a lock.")
            delay(300)
            if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) ToolResult.ok("Boss, locked.")
            else ToolResult.ok("Boss, I requested a screen lock.")
        },
    )
}

/** Available on older Android versions; modern Android requires the settings page or root. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun bluetoothFallback(context: Context, on: Boolean): Boolean = runCatching {
    context.getSystemService(BluetoothManager::class.java).adapter?.let { if (on) it.enable() else it.disable() } ?: false
}.getOrDefault(false)

/** Used by unlock_phone (risk VOICE_ID) and the panic word. */
object Keyguard {
    suspend fun unlock(context: Context, pin: String?): Boolean {
        if (!RootShell.available) return false
        val dm = context.resources.displayMetrics
        val x = dm.widthPixels / 2
        RootShell.run("input keyevent 224")   // wake up
        RootShell.run("input swipe $x ${dm.heightPixels * 85 / 100} $x ${dm.heightPixels * 30 / 100} 200")
        if (pin.isNullOrEmpty()) return true
        kotlinx.coroutines.delay(400)
        return RootShell.run("input text ${RootShell.quote(pin)}", "input keyevent 66").ok
    }
}

fun unlockTool(context: Context, pin: suspend () -> String?) = SimpleTool("unlock_phone", "Unlock the phone", risk = Risk.VOICE_ID) { _, _ ->
    val p = pin()
    if (!Keyguard.unlock(context, p)) return@SimpleTool ToolResult.fail("Boss, I couldn't request an unlock. Please unlock the phone yourself.")
    val keyguard = context.getSystemService(KeyguardManager::class.java)
    pollUntil(20, 100) { !keyguard.isKeyguardLocked }   // PIN check can take a moment on MIUI
    if (!keyguard.isKeyguardLocked) ToolResult.ok("Boss, unlocked.")
    else ToolResult.fail("Boss, the phone is still locked.")
}

internal fun requestedToggleState(request: String?, current: Boolean?): Boolean? = when (request) {
    "on" -> true
    "off" -> false
    else -> current?.not()
}

internal fun toggleTruth(label: String, wanted: Boolean, actual: Boolean?): ToolResult = when {
    actual == null -> ToolResult.fail("Boss, I requested the change, but couldn't verify $label.")
    actual == wanted -> ToolResult.ok("Boss, $label is ${if (actual) "on" else "off"}.")
    else -> ToolResult.fail("Boss, $label is still ${if (actual) "on" else "off"}.")
}

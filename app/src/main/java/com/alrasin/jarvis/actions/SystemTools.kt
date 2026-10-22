package com.alrasin.jarvis.actions

import android.annotation.SuppressLint
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Flashlight. Camera service is opened on first use, so a camera problem never breaks the tool list. */
class Torch(private val context: Context) {
    @Volatile private var state = false
    /** Set when the LED was switched with root; the camera callback does not see that (it reports off). */
    @Volatile private var ledOn = false
    val on: Boolean get() = ledOn || runCatching { cm; state }.getOrDefault(false)

    private val cm: CameraManager by lazy {
        context.getSystemService(CameraManager::class.java).also { cm ->
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == id) state = enabled }
            }, null)
        }
    }
    private val id: String? by lazy {
        runCatching { cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
            .getOrNull()
    }

    /** Camera API first; MIUI refuses it for background apps, then the flash LED is switched with root. */
    suspend fun set(value: Boolean): Boolean {
        val api = runCatching { id?.let { cm.setTorchMode(it, value); true } ?: false }
            .onFailure { JarvisLog.e("torch: camera API failed", it) }.getOrDefault(false)
        if (api) { ledOn = false; return true }
        val ok = RootShell.run("for l in $LEDS; do [ -w \$l/brightness ] && echo ${if (value) LED_ON else 0} > \$l/brightness; done; true").ok
        if (ok) ledOn = value
        return ok
    }

    companion object {
        /** MediaTek flash LEDs (Redmi 10 Prime: torch-light0). */
        private const val LEDS = "/sys/class/leds/torch-light0 /sys/class/leds/flashlight"
        private const val LED_ON = 200
    }
}

val TOGGLE_TARGETS = listOf("flashlight", "wifi", "bluetooth", "data", "dnd", "airplane", "location", "hotspot", "battery_saver", "auto_rotate")

fun systemTools(context: Context, torch: Torch): List<Tool> {
    fun isOn(target: String): Boolean? = when (target) {
        "flashlight" -> torch.on
        "wifi" -> context.getSystemService(WifiManager::class.java).isWifiEnabled
        "bluetooth" -> context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled
        "data" -> Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 1
        "dnd" -> context.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        "airplane" -> Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        "location" -> context.getSystemService(LocationManager::class.java).isLocationEnabled
        "battery_saver" -> context.getSystemService(PowerManager::class.java).isPowerSaveMode
        "auto_rotate" -> Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
        else -> null
    }

    suspend fun set(target: String, on: Boolean): Boolean {
        val e = if (on) "enable" else "disable"
        return when (target) {
            "flashlight" -> torch.set(on)
            "wifi" -> RootShell.run("svc wifi $e").ok
            "bluetooth" -> RootShell.run("svc bluetooth $e").ok ||
                bluetoothFallback(context, on)
            "data" -> RootShell.run("svc data $e").ok
            "dnd" -> RootShell.run("cmd notification set_dnd ${if (on) "priority" else "off"}").ok
            "airplane" -> RootShell.run("cmd connectivity airplane-mode $e").ok
            "location" -> RootShell.run(
                "settings put secure location_mode ${if (on) 3 else 0}",
                "cmd location set-location-enabled $on 2>/dev/null; true",
            ).ok
            "hotspot" -> {
                // no stable shell command on Android 11: open the hotspot page instead
                context.launch(Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"))
            }
            "battery_saver" -> RootShell.run("cmd power set-mode ${if (on) 1 else 0}").ok
            "auto_rotate" -> RootShell.run("settings put system accelerometer_rotation ${if (on) 1 else 0}").ok
            else -> false
        }
    }

    fun nice(target: String) = when (target) {
        "dnd" -> "Do not disturb"; "data" -> "Mobile data"; "battery_saver" -> "Battery saver"
        "auto_rotate" -> "Auto rotate"; "wifi" -> "Wi-Fi"; "airplane" -> "Airplane mode"
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
            val want = when (c.arg("state")) { "on" -> true; "off" -> false; else -> !(isOn(target) ?: false) }
            if (target == "hotspot") {
                set(target, want)
                return@SimpleTool ToolResult.ok("I opened the hotspot page. Tap the switch.")
            }
            if (isOn(target) == want) return@SimpleTool ToolResult.ok("${nice(target)} is already ${if (want) "on" else "off"}.")
            if (!set(target, want)) return@SimpleTool ToolResult.fail("I couldn't switch ${nice(target)}. Is root allowed?")
            ToolResult.ok("${nice(target)} ${if (want) "on" else "off"}.")
        },
        SimpleTool("set_brightness", "Screen brightness", listOf(arg("level", "0-100, up, down or auto"))) { c, _ ->
            val cur = Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, maxBrightness / 2)
            val target = when (val l = c.arg("level")) {
                "auto" -> {
                    RootShell.run("settings put system screen_brightness_mode 1")
                    return@SimpleTool ToolResult.ok("Auto brightness on.")
                }
                "up" -> cur + maxBrightness / 5
                "down" -> cur - maxBrightness / 5
                else -> (l?.toDoubleOrNull()?.toInt() ?: return@SimpleTool ToolResult.fail("What brightness?")) * maxBrightness / 100
            }.coerceIn(1, maxBrightness)
            val r = RootShell.run("settings put system screen_brightness_mode 0", "settings put system screen_brightness $target")
            if (r.ok) ToolResult.ok("Brightness ${target * 100 / maxBrightness} percent.") else ToolResult.fail("I couldn't change brightness.")
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
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Screenshots")
            val f = File(dir, "Screenshot_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}_Jarvis.png")
            val r = RootShell.run("mkdir -p ${RootShell.quote(dir.path)}", "screencap -p ${RootShell.quote(f.path)}")
            if (!r.ok) return@SimpleTool ToolResult.fail("Screenshot failed.")
            MediaScannerConnection.scanFile(context, arrayOf(f.path), null, null)
            ToolResult.ok("Screenshot saved.")
        },
        SimpleTool("lock_phone", "Lock the phone (screen off)") { _, _ ->
            val a = JarvisAccessibilityService.instance
            if (a?.lockScreen() == true || RootShell.run("input keyevent 223").ok) ToolResult.ok("Locked.") else ToolResult.fail("I couldn't lock the phone.")
        },
    )
}

/** Old API, still works on Android 11 when root `svc` fails. */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
private fun bluetoothFallback(context: Context, on: Boolean): Boolean = runCatching {
    context.getSystemService(BluetoothManager::class.java).adapter?.let { if (on) it.enable() else it.disable() } ?: false
}.getOrDefault(false)

/** Used by unlock_phone (risk VOICE_ID) and the panic word. */
object Keyguard {
    suspend fun unlock(context: Context, pin: String?): Boolean {
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
    if (Keyguard.unlock(context, p)) ToolResult.ok(if (p == null) "Screen is on. No PIN saved, so I only swiped." else "Unlocked.")
    else ToolResult.fail("I couldn't unlock.")
}

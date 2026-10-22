package com.alrasin.jarvis.ui

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.alrasin.jarvis.actions.JarvisAccessibilityService
import com.alrasin.jarvis.notify.JarvisNotificationListener

data class DeviceFeature(
    val id: String,
    val label: String,
    val ready: Boolean,
    val detail: String,
    val action: String? = null,
    val packageUri: Boolean = false,
)

object DeviceCheck {
    fun hasPermission(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun features(context: Context): List<DeviceFeature> {
        val resolver = context.contentResolver
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        val role = context.getSystemService(RoleManager::class.java)
        fun componentEnabled(key: String, component: ComponentName): Boolean =
            Settings.Secure.getString(resolver, key).orEmpty().split(':')
                .mapNotNull(ComponentName::unflattenFromString).contains(component)
        val assistant = role.isRoleAvailable(RoleManager.ROLE_ASSISTANT) && role.isRoleHeld(RoleManager.ROLE_ASSISTANT)
        return buildList {
            add(DeviceFeature("mic", "Microphone", hasPermission(context, Manifest.permission.RECORD_AUDIO), "Needed for Hey Jimi and voice commands."))
            add(DeviceFeature("accessibility", "Accessibility", componentEnabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ComponentName(context, JarvisAccessibilityService::class.java)), "Read and control the screen without root.", Settings.ACTION_ACCESSIBILITY_SETTINGS))
            add(DeviceFeature("notification", "Notification access", notificationManager.isNotificationListenerAccessGranted(
                ComponentName(context, JarvisNotificationListener::class.java)), "Read and manage notifications.", Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            add(DeviceFeature("assistant", "Default assistant", assistant, "Optional beside Gemini. Choose Jimi to replace Gemini.", Settings.ACTION_VOICE_INPUT_SETTINGS))
            add(DeviceFeature("battery", "Battery whitelist", context.getSystemService(PowerManager::class.java)
                .isIgnoringBatteryOptimizations(context.packageName), "Allow background listening. Some brands also need manual autostart permission.",
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true))
            add(DeviceFeature("overlay", "Display over other apps", Settings.canDrawOverlays(context), "Show Jimi's panel from background.", Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true))
            add(DeviceFeature("write_settings", "Change system settings", Settings.System.canWrite(context), "Brightness and compatible device settings.", Settings.ACTION_MANAGE_WRITE_SETTINGS, true))
            add(DeviceFeature("dnd", "Do Not Disturb access", notificationManager.isNotificationPolicyAccessGranted,
                "Allow routines to change Do Not Disturb.", Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            if (Build.VERSION.SDK_INT >= 31) add(DeviceFeature("alarms", "Exact alarms",
                context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), "Deliver reminders on time.", Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true))
            if (Build.VERSION.SDK_INT >= 34) add(DeviceFeature("full_screen", "Full-screen notifications",
                notificationManager.canUseFullScreenIntent(), "Show assistant and reminder notifications over the lock screen.", Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, true))
        }
    }

    fun open(context: Context, action: String, packageUri: Boolean = false): Boolean {
        val intent = Intent(action).apply {
            if (packageUri) data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent); true }.getOrElse {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                false
            }.getOrDefault(false)
        }
    }
}

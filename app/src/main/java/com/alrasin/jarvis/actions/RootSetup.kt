package com.alrasin.jarvis.actions

import android.content.Context
import android.os.Build
import com.alrasin.jarvis.core.isInstalled
import com.alrasin.jarvis.notify.JarvisNotificationListener

/**
 * One-tap setup with root (PLAN 2.x / 4.x): grants every permission Jimi needs, turns on the
 * accessibility + notification services, and keeps MIUI from killing the app. Safe to run again.
 */
object RootSetup {
    private val RUNTIME = listOf(
        "RECORD_AUDIO", "POST_NOTIFICATIONS", "READ_CONTACTS", "CALL_PHONE", "SEND_SMS", "READ_SMS", "RECEIVE_SMS",
        "READ_PHONE_STATE", "READ_CALL_LOG", "WRITE_CONTACTS", "ANSWER_PHONE_CALLS", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION", "BLUETOOTH_CONNECT",
        "READ_EXTERNAL_STORAGE", "READ_CALENDAR", "WRITE_CALENDAR",
    )

    /** Returns one line per step: "ok …" or "fail …". */
    suspend fun run(context: Context, replaceAssistant: Boolean = false): List<String> {
        if (!RootShell.available) return listOf("Root unavailable. Use Setup to grant permissions through Android settings.")
        val pkg = context.packageName
        val out = ArrayList<String>()
        suspend fun step(name: String, vararg cmds: String) {
            out += (if (RootShell.run(*cmds).ok) "ok " else "fail ") + name
        }
        RUNTIME.filter {
            when (it) {
                "POST_NOTIFICATIONS" -> Build.VERSION.SDK_INT >= 33
                "BLUETOOTH_CONNECT" -> Build.VERSION.SDK_INT >= 31
                "READ_EXTERNAL_STORAGE" -> Build.VERSION.SDK_INT <= 32
                else -> true
            }
        }.forEach { step("permission $it", "pm grant $pkg android.permission.$it") }
        step("display over apps", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        if (DeviceActionPolicy.hasMiuiSettings(Build.MANUFACTURER) && context.isInstalled("com.miui.securitycenter")) {
            step("MIUI: start in background", "appops set $pkg 10021 allow")
            step("MIUI: show on lock screen", "appops set $pkg 10020 allow")
        }
        step("change settings", "appops set $pkg WRITE_SETTINGS allow")
        if (Build.VERSION.SDK_INT >= 31) step("exact alarms", "appops set $pkg SCHEDULE_EXACT_ALARM allow")
        step("battery: never sleep", "dumpsys deviceidle whitelist +$pkg")
        step("do not disturb access", "cmd notification allow_dnd $pkg")
        step("notification access", "cmd notification allow_listener ${JarvisNotificationListener.COMPONENT}")
        val vis = "$pkg/com.alrasin.jarvis.assist.JarvisVoiceInteractionService"
        if (replaceAssistant) step(
            "default assistant (instead of Google / Gemini)",
            "cmd role add-role-holder android.app.role.ASSISTANT $pkg 0 2>/dev/null || true",
            "settings put secure assistant $vis",
            "settings put secure voice_interaction_service $vis",
            "settings put secure voice_recognition_service $pkg/com.alrasin.jarvis.assist.JarvisRecognitionService",
        )
        out += (if (enableAccessibility()) "ok " else "fail ") + "accessibility"
        return out
    }

    /** Adds Jimi's accessibility service to the enabled list (keeps the others) and turns accessibility on.
     * [restart]: remove it first so Android binds it again (listed but not running after MIUI killed it). */
    suspend fun enableAccessibility(restart: Boolean = false): Boolean {
        val a11y = JarvisAccessibilityService.COMPONENT
        if (restart) RootShell.run(
            "cur=\$(settings get secure enabled_accessibility_services | sed -e 's#$a11y##g' -e 's#::*#:#g' -e 's#^:##' -e 's#:\$##'); " +
                "settings put secure enabled_accessibility_services \"\${cur:-null}\"",
        )
        return RootShell.run(
            "cur=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$cur\" in *$a11y*) ;; null|'') settings put secure enabled_accessibility_services $a11y ;; " +
                "*) settings put secure enabled_accessibility_services \"\$cur:$a11y\" ;; esac",
            "settings put secure accessibility_enabled 1",
        ).ok
    }
}

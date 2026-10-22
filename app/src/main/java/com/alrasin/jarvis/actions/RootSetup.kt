package com.alrasin.jarvis.actions

import android.content.Context
import com.alrasin.jarvis.notify.JarvisNotificationListener

/**
 * One-tap setup with root (PLAN 2.x / 4.x): grants every permission Jimi needs, turns on the
 * accessibility + notification services, and keeps MIUI from killing the app. Safe to run again.
 */
object RootSetup {
    private val RUNTIME = listOf(
        "RECORD_AUDIO", "POST_NOTIFICATIONS", "READ_CONTACTS", "CALL_PHONE", "SEND_SMS", "READ_SMS",
        "READ_PHONE_STATE", "CAMERA", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "BLUETOOTH_CONNECT",
        "READ_EXTERNAL_STORAGE",
    )

    /** Returns one line per step: "ok …" or "fail …". */
    suspend fun run(context: Context): List<String> {
        if (!RootShell.available) return listOf("fail: no root (allow Jarvis in Magisk)")
        val pkg = context.packageName
        val out = ArrayList<String>()
        suspend fun step(name: String, vararg cmds: String) {
            out += (if (RootShell.run(*cmds).ok) "ok " else "fail ") + name
        }
        RUNTIME.forEach { step("permission $it", "pm grant $pkg android.permission.$it 2>/dev/null || true") }
        step("display over apps", "appops set $pkg SYSTEM_ALERT_WINDOW allow")
        step("MIUI: start in background", "appops set $pkg 10021 allow 2>/dev/null || true")
        step("MIUI: show on lock screen", "appops set $pkg 10020 allow 2>/dev/null || true")
        step("change settings", "appops set $pkg WRITE_SETTINGS allow")
        step("exact alarms", "appops set $pkg SCHEDULE_EXACT_ALARM allow 2>/dev/null || true")
        step("battery: never sleep", "dumpsys deviceidle whitelist +$pkg")
        step("do not disturb access", "cmd notification allow_dnd $pkg")
        step("notification access", "cmd notification allow_listener ${JarvisNotificationListener.COMPONENT}")
        val a11y = JarvisAccessibilityService.COMPONENT
        step(
            "accessibility",
            "cur=\$(settings get secure enabled_accessibility_services); " +
                "case \"\$cur\" in *$a11y*) ;; null|'') settings put secure enabled_accessibility_services $a11y ;; " +
                "*) settings put secure enabled_accessibility_services \"\$cur:$a11y\" ;; esac",
            "settings put secure accessibility_enabled 1",
        )
        return out
    }
}

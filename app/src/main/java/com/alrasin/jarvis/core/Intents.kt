package com.alrasin.jarvis.core

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.alrasin.jarvis.actions.AppList

/** Names that mean "open the Settings app" everywhere a user says "settings"/"phone settings" (ROADMAP §L.5). */
private val SETTINGS_NAMES = setOf("settings", "phone settings", "com.android.settings")

/** Starts an activity from the service (background start is allowed: overlay permission, granted by root at setup). */
fun Context.launch(intent: Intent): Boolean = runCatching {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
}.getOrDefault(false)

/** True when [pkg] is installed. */
fun Context.isInstalled(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/** True when some activity can handle [intent] (a public settings page exists, a viewer app is installed, …). */
fun Context.resolves(intent: Intent): Boolean = intent.resolveActivity(packageManager) != null

/**
 * The intent that opens the app named or packaged [name] ("settings"/"phone settings" opens Android Settings);
 * never points back at Jarvis itself. Exact package/label match wins over [AppList]'s fuzzy [AppList.find].
 */
fun Context.resolveAppIntent(apps: AppList, name: String): Intent? {
    if (name.lowercase() in SETTINGS_NAMES) return Intent(Settings.ACTION_SETTINGS)
    val matches = apps.all().filter { it.pkg == name || it.label.equals(name, ignoreCase = true) }
    val app = matches.singleOrNull() ?: apps.find(name) ?: return null
    if (app.pkg == packageName) return null
    return packageManager.getLaunchIntentForPackage(app.pkg)
}

/**
 * Opens the app named or packaged [name]. [beforeAction] runs right before the activity starts (e.g. a
 * cancellation check). Dedupes AppTools' open_app tool and the screen agent's OpenApp action (ROADMAP §L.5).
 */
fun Context.openApp(apps: AppList, name: String, beforeAction: () -> Unit = {}): Boolean {
    val intent = resolveAppIntent(apps, name) ?: return false
    beforeAction()
    return launch(intent)
}

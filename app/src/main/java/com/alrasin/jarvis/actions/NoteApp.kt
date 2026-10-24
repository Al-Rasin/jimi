package com.alrasin.jarvis.actions

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.isLocked
import com.alrasin.jarvis.core.pollUntil

/**
 * Saves a note in the phone's own notes app (D-056), found from Android, not a hand list: first the app that handles
 * the voice-assistant CREATE_NOTE intent (Google Keep), else a share target that looks like a notes app.
 * The app shows its note card/editor; the accessibility service presses Save (or Back, which saves in editors).
 */
class NoteApp(private val context: Context) {
    data class Target(val pkg: String, val label: String, val createNote: Boolean)

    fun target(): Target? {
        val pm = context.packageManager
        fun handlers(action: String) = pm.queryIntentActivities(Intent(action).setType("text/plain"), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        val create = handlers(CREATE_NOTE).map { it.first }
        val share = handlers(Intent.ACTION_SEND).map { (pkg, label) -> pkg to (runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(label)) }
        return pick(create, share)?.let { (pkg, viaCreate) ->
            Target(pkg, share.firstOrNull { it.first == pkg }?.second ?: appLabel(pkg), viaCreate)
        }
    }

    private fun appLabel(pkg: String) = runCatching {
        context.packageManager.let { it.getApplicationLabel(it.getApplicationInfo(pkg, 0)).toString() }
    }.getOrDefault("your notes app")

    /** Label of the app it was saved in, or null (locked, no notes app, no accessibility, or Save not found). */
    suspend fun save(text: String, title: String? = null): String? {
        val t = target() ?: return null
        if (context.isLocked()) return null
        val service = requireScreen { return null }
        val intent = Intent(if (t.createNote) CREATE_NOTE else Intent.ACTION_SEND).setType("text/plain").setPackage(t.pkg)
            .putExtra(Intent.EXTRA_TEXT, text)
        title?.let { intent.putExtra(Intent.EXTRA_SUBJECT, it) }
        if (!context.launch(intent) || !pollUntil(20, 250) { service.hasWindow(t.pkg) }) return null
        if (pollUntil(20, 250) { service.clickExact(t.pkg, labels = SAVE_LABELS) }) return t.label.also { JarvisLog.i("note saved in ${t.pkg}") }
        // Editors without a Save button (MIUI Notes) save on Back, once the text is in.
        if (service.editableText(t.pkg)?.contains(text.take(20)) == true) {
            service.global(AccessibilityService.GLOBAL_ACTION_BACK)
            return t.label.also { JarvisLog.i("note saved in ${t.pkg} with Back") }
        }
        JarvisLog.i("note: no Save in ${t.pkg}")
        return null
    }

    companion object {
        const val CREATE_NOTE = "com.google.android.gms.actions.CREATE_NOTE"
        private val SAVE_LABELS = listOf("Save", "Save note", "Done", "Add", "Add note", "Create")
        private val NOTE_WORDS = Regex("note|\\bkeep\\b|memo|notion|obsidian|evernote|journal", RegexOption.IGNORE_CASE)

        /** (package, true = CREATE_NOTE) — the CREATE_NOTE app first, else a share target named like a notes app. */
        fun pick(createNote: List<String>, share: List<Pair<String, String>>): Pair<String, Boolean>? =
            createNote.firstOrNull()?.let { it to true }
                ?: share.firstOrNull { (pkg, label) -> NOTE_WORDS.containsMatchIn(pkg) || NOTE_WORDS.containsMatchIn(label) }?.let { it.first to false }
    }
}

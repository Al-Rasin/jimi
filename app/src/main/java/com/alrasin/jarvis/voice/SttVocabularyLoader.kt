package com.alrasin.jarvis.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract

class SttVocabularyLoader(private val context: Context) {
    fun load(): SttVocabulary = SttVocabulary(
        contacts = available { contactNames() },
        apps = available { appNames() },
    )

    private fun contactNames(): List<String> {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        val names = ArrayList<String>()
        val column = ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(column),
            null,
            null,
            "$column COLLATE NOCASE ASC, ${ContactsContract.Contacts._ID} ASC",
        )?.use { cursor ->
            var scanned = 0
            while (scanned < MAX_SCANNED_CONTACTS && cursor.moveToNext()) {
                scanned++
                if (!cursor.isNull(0)) SttVocabulary.sanitize(cursor.getString(0))?.let(names::add)
            }
        }
        return names
    }

    @Suppress("DEPRECATION")
    private fun appNames(): List<String> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .sortedBy { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
            .take(MAX_SCANNED_APPS)
            .mapNotNull { activity ->
                try {
                    SttVocabulary.sanitize(activity.loadLabel(pm).toString())
                } catch (_: RuntimeException) {
                    null
                }
            }
    }

    private inline fun available(load: () -> List<String>): List<String> = try {
        load()
    } catch (_: RuntimeException) {
        // Permissions can be revoked or a provider can disappear while the service is alive.
        emptyList()
    }

    private companion object {
        const val MAX_SCANNED_CONTACTS = 4096
        const val MAX_SCANNED_APPS = 2048
    }
}

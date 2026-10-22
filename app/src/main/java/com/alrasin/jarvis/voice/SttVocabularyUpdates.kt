package com.alrasin.jarvis.voice

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SttVocabularyUpdates(context: Context) {
    private val context = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    fun snapshots(): Flow<SttVocabulary> = changes().mapLatest {
        delay(250)
        withContext(Dispatchers.IO) { SttVocabularyLoader(context).load() }
    }.distinctUntilChangedBy { it.contacts to it.apps }

    private fun changes(): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { trySend(Unit) }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { trySend(Unit) }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        var observing = false
        fun registerContacts() {
            if (!observing) observing = runCatching {
                context.contentResolver.registerContentObserver(ContactsContract.Contacts.CONTENT_URI, true, observer)
                true
            }.getOrDefault(false)
        }
        registerContacts()
        @Suppress("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        trySend(Unit)
        val permissionWatcher = launch {
            var permission = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
            while (true) {
                delay(2_000)
                val current = context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                if (current != permission) {
                    permission = current
                    registerContacts()
                    trySend(Unit)
                }
            }
        }
        awaitClose {
            permissionWatcher.cancel()
            if (observing) context.contentResolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }.buffer(Channel.CONFLATED)
}

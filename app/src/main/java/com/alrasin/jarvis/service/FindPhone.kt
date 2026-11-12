package com.alrasin.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.provider.Telephony
import android.telephony.SmsManager
import com.alrasin.jarvis.actions.CurrentLocation
import com.alrasin.jarvis.actions.RootShell
import com.alrasin.jarvis.actions.Torch
import com.alrasin.jarvis.actions.locationData
import com.alrasin.jarvis.core.JarvisLog
import com.alrasin.jarvis.core.PhoneNumbers
import com.alrasin.jarvis.core.Secrets
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Find my phone (ROADMAP #30, §I.6): an SMS from a trusted number that holds the secret word makes the phone
 * ring at full volume even on silent / DND, flash the torch, and reply with its location.
 * The word and numbers are set in Settings and kept encrypted ([Secrets]). Matching is pure and unit tested.
 */
object FindPhoneMatch {
    /** "01712 345678, +8801812…" → number tails; junk parts are dropped. */
    fun trusted(list: String?): List<String> = list.orEmpty().split(',', ';', '\n')
        .mapNotNull { PhoneNumbers.parse(it) }.map(PhoneNumbers::tail).filter { it.length >= 5 }.distinct()

    /** True when [from] is trusted and [body] holds [secret] as whole word(s), any case. */
    fun matches(from: String?, body: String, trustedList: String?, secret: String?): Boolean {
        val word = secret?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")?.takeIf { it.length >= 4 } ?: return false
        val tails = trusted(trustedList)
        if (from == null || tails.isEmpty() || tails.none { PhoneNumbers.endsWith(from, it) }) return false
        val text = body.lowercase().replace(Regex("\\s+"), " ")
        return Regex("(^|\\W)${Regex.escape(word)}(\\W|$)").containsMatchIn(text)
    }

    fun reply(location: String?): String =
        if (location == null) "Jimi: phone is ringing. Location not available right now."
        else "Jimi: phone is ringing. Location: $location"
}

@AndroidEntryPoint
class FindPhoneReceiver : BroadcastReceiver() {
    @Inject lateinit var secrets: Secrets

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull().orEmpty()
        val from = msgs.firstOrNull()?.originatingAddress
        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        if (!FindPhoneMatch.matches(from, body, secrets.get(Secrets.FIND_NUMBERS), secrets.get(Secrets.FIND_WORD))) return
        JarvisLog.i("find phone: secret SMS received")
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                launch { FindPhone.ring(app) }
                val fix = runCatching { CurrentLocation(app).get(timeoutMs = 15_000) }.getOrNull()
                FindPhone.replySms(from!!, FindPhoneMatch.reply(fix?.let(::locationData)?.replace('\n', ' ')))
            } catch (e: Exception) {
                JarvisLog.e("find phone failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}

object FindPhone {
    private const val RING_MS = 60_000L
    private const val FLASH_MS = 500L

    /** Alarm stream at max: alarms pass silent mode; root also turns DND off in case it blocks alarms. */
    suspend fun ring(context: Context) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (RootShell.available) RootShell.run("cmd notification set_dnd off", timeoutMs = 3_000, log = false)
        runCatching { audio.ringerMode = AudioManager.RINGER_MODE_NORMAL }
        val old = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        }.onFailure { JarvisLog.e("find phone: ring failed", it) }.getOrNull()
        val torch = Torch(context)
        var on = false
        val end = System.currentTimeMillis() + RING_MS
        while (System.currentTimeMillis() < end) {
            on = !on
            runCatching { torch.set(on) }
            delay(FLASH_MS)
        }
        runCatching { torch.set(false) }
        withContext(Dispatchers.Main) { runCatching { player?.stop(); player?.release() } }
        audio.setStreamVolume(AudioManager.STREAM_ALARM, old, 0)
    }

    fun replySms(to: String, text: String) {
        runCatching {
            @Suppress("DEPRECATION") val sms = SmsManager.getDefault()
            sms.sendMultipartTextMessage(to, null, sms.divideMessage(text), null, null)
        }.onFailure { JarvisLog.e("find phone: reply failed", it) }
    }
}

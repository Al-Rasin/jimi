package com.alrasin.jarvis.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** PIN and API keys, AES-GCM with a key that never leaves Android Keystore (D-031). Values never logged. */
@Singleton
class Secrets @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    fun put(name: String, value: String?) {
        if (value.isNullOrEmpty()) { prefs.edit().remove(name).apply(); return }
        val c = Cipher.getInstance(MODE).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = c.iv + c.doFinal(value.toByteArray())
        prefs.edit().putString(name, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? = runCatching {
        val data = Base64.decode(prefs.getString(name, null) ?: return null, Base64.NO_WRAP)
        val c = Cipher.getInstance(MODE).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12)) }
        String(c.doFinal(data, 12, data.size - 12))
    }.getOrNull()

    fun has(name: String) = prefs.contains(name)

    companion object {
        private const val ALIAS = "jarvis_secrets"
        private const val MODE = "AES/GCM/NoPadding"
        const val PIN = "pin"
        const val CLOUD_KEY = "cloud_api_key"
        /** Kimi was removed (D-065); only used to delete an old saved key. */
        const val OLD_KIMI_KEY = "kimi_api_key"
        const val GROQ_KEY = "groq_api_key"
        const val OPENROUTER_KEY = "openrouter_api_key"
        const val CLOUDFLARE_KEY = "cloudflare_api_token"
        const val CLOUDFLARE_ACCOUNT = "cloudflare_account_id"

        /** Any of these saved = cloud can run (Cloudflare needs its account ID too). */
        val CLOUD_KEYS = listOf(CLOUD_KEY, GROQ_KEY, OPENROUTER_KEY, CLOUDFLARE_KEY)
    }
}

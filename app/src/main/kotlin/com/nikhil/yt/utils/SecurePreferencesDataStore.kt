package com.nikhil.yt.utils

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Store secrets under a non-exportable Android Keystore AES-256-GCM key.
 * All existing DataStore callers continue to read/write plaintext Preferences in
 * memory; only the on-disk representation of sensitive String preferences changes.
 *
 * Auto-migrates legacy plaintext values on the next edit. App triggers a
 * migration edit at startup. Nothing is backed up to Android cloud backup.
 */
internal object SecurePreferencesDataStore {
    private val Context.diskPreferences: DataStore<Preferences> by
        preferencesDataStore(name = "settings")

    @Volatile private var shared: DataStore<Preferences>? = null

    fun forContext(context: Context): DataStore<Preferences> {
        shared?.let { return it }
        return synchronized(this) {
            shared ?: ProtectedDataStore(context.applicationContext.diskPreferences)
                .also { shared = it }
        }
    }

    internal fun isSensitivePreference(name: String): Boolean {
        val normalized = name.lowercase(java.util.Locale.ROOT).replace("-", "_")
        return listOf(
            "cookie", "token", "password", "secret", "sessionkey", "session_key",
            "visitor", "datasync", "data_sync", "oauth", "authorization",
            "sapisid", "accountemail", "account_email"
        ).any { normalized.contains(it) }
    }

    private class ProtectedDataStore(private val underlying: DataStore<Preferences>) :
        DataStore<Preferences> {
        private val cipher = KeystoreCipher()

        override val data: Flow<Preferences> = underlying.data.map { decryptPreferences(it) }

        override suspend fun updateData(
            transform: suspend (t: Preferences) -> Preferences,
        ): Preferences {
            val stored = underlying.updateData { old ->
                val clear = decryptPreferences(old)
                val updated = transform(clear)
                // Avoid rewriting a random IV on every application startup.
                if (updated == clear && !containsLegacyPlainSecrets(old)) old
                else encryptPreferences(updated, clear, old)
            }
            return decryptPreferences(stored)
        }

        private fun containsLegacyPlainSecrets(prefs: Preferences): Boolean =
            prefs.asMap().any { (key, value) ->
                isSensitivePreference(key.name) && value is String &&
                    value.isNotBlank() && !value.startsWith(KeystoreCipher.PREFIX)
            }

        private fun decryptPreferences(raw: Preferences): Preferences {
            val result = raw.toMutablePreferences()
            raw.asMap().forEach { (key, value) ->
                if (isSensitivePreference(key.name) && value is String &&
                    value.startsWith(KeystoreCipher.PREFIX)
                ) {
                    val name = stringPreferencesKey(key.name)
                    val decrypted = runCatching { cipher.decrypt(value) }.getOrNull()
                    // A wiped/invalid device key must never expose an encrypted
                    // credential as plaintext to callers. Treat it as signed-out.
                    if (decrypted == null) result.remove(name)
                    else result[name] = decrypted
                }
            }
            return result.toPreferences()
        }

        private fun encryptPreferences(
            clear: Preferences,
            priorPlain: Preferences,
            priorEncrypted: Preferences,
        ): Preferences {
            val result = clear.toMutablePreferences()
            clear.asMap().forEach { (key, value) ->
                if (isSensitivePreference(key.name) && value is String && value.isNotBlank()) {
                    val preferenceKey = stringPreferencesKey(key.name)
                    val oldCiphertext = priorEncrypted[preferenceKey]
                    // Only encrypt changed credentials. Toggling a theme or moving a
                    // slider must not churn all Keystore values and drain energy.
                    result[preferenceKey] =
                        if (oldCiphertext?.startsWith(KeystoreCipher.PREFIX) == true &&
                            priorPlain[preferenceKey] == value
                        ) oldCiphertext
                        else cipher.encrypt(value)
                }
            }
            return result.toPreferences()
        }
    }

    private class KeystoreCipher {
        private val secretKey: SecretKey by lazy {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = store.getKey(ALIAS, null) as? SecretKey
            existing ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
        }

        fun encrypt(value: String): String {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, secretKey)
            val ciphertext = aes.doFinal(value.toByteArray(StandardCharsets.UTF_8))
            return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(aes.iv + ciphertext)
        }

        fun decrypt(encoded: String): String {
            require(encoded.startsWith(PREFIX))
            val data = Base64.getUrlDecoder().decode(encoded.removePrefix(PREFIX))
            require(data.size >= 12 + 16)
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, data, 0, 12))
            return String(aes.doFinal(data, 12, data.size - 12), StandardCharsets.UTF_8)
        }

        companion object {
            const val PREFIX = "capsule-keystore-v1:"
            const val ALIAS = "capsule_music_preferences_aes_v1"
        }
    }
}

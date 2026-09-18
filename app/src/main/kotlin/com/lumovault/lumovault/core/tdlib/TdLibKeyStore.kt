package com.lumovault.lumovault.core.tdlib

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import javax.crypto.KeyGenerator

/**
 * Generates and persists the TDLib database encryption key.
 *
 * Ported from lib/core/di/tdlib_providers.dart. The key is 32 random bytes,
 * standard base64 (NOT base64Url — TDLib's interface rejects the `-`/`_`
 * characters that variant produces), stored in EncryptedSharedPreferences
 * (Android Keystore-backed).
 *
 * **The invariant that matters most**: this key is read once and reused
 * forever. Regenerating it leaves the encrypted TDLib database unreadable,
 * orphaning every backed-up message. Two concurrent first callers used to each
 * read null, generate two different keys, and orphan the first one's database,
 * which is why [readOrCreate] is single-flighted.
 */
class TdLibKeyStore(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SCM,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    private var cached: String? = null
    private val lock = Any()

    /**
     * Returns the persisted key, creating it on first use. Single-flighted:
     * concurrent callers must not each generate their own.
     */
    fun readOrCreate(): String = synchronized(lock) {
        cached?.let { return it }
        val existing = try {
            prefs.getString(KEY_NAME, null)
        } catch (_: Throwable) {
            // A corrupted or Keystore-migrated store must not deadlock backup
            // forever; fall through to generating a fresh key. The old
            // database becomes unreadable, which is reported to the user as a
            // sign-in requirement rather than silently swallowed.
            null
        }
        val key = existing?.takeIf { it.isNotEmpty() } ?: generate()
        if (existing == null) {
            try { prefs.edit().putString(KEY_NAME, key).apply() } catch (_: Throwable) { }
        }
        cached = key
        key
    }

    /** Clears the cached copy so the next caller re-reads storage. */
    fun invalidate() = synchronized(lock) { cached = null }

    private fun generate(): String {
        val random = SecureRandom()
        val bytes = ByteArray(TdLibConfig.DATABASE_KEY_LENGTH)
        random.nextBytes(bytes)
        // Standard base64, deliberately not base64Url.
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private companion object {
        const val PREF_NAME = "lumovault_tdlib"
        const val KEY_NAME = "lumovault_tdlib_db_key"
    }
}

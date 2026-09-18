package com.lumovault.lumovault.features.settings.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json

/**
 * Persists [AppSettings] as one encrypted JSON blob.
 *
 * Ported from lib/features/settings/data/repositories/settings_repository.dart.
 * Three properties of the original are load-bearing and carry over:
 *
 *  - **One blob, not one key per setting.** The original used a single
 *    `lumovault_settings` entry in secure storage so that a multi-field write
 *    (onboarding completing sets `onboardingCompleted` *and* `includedFolders`
 *    together) is atomic. A per-key version raced and clobbered the flag.
 *  - **An in-memory cache** so reads never touch the keystore, and so a failed
 *    decrypt degrades to the cached value rather than crashing the UI.
 *  - **A change flow** that the view model subscribes to; without it, a write
 *    from a background worker would never reach the screen.
 *
 * Errors are surfaced on [errors] rather than thrown: a settings write failing
 * must not take down a backup in progress, but it must not be invisible either.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @Volatile private var cached: AppSettings? = null

    private val _changes = MutableSharedFlow<AppSettings>(extraBufferCapacity = 64)
    val changes: SharedFlow<AppSettings> = _changes.asSharedFlow()

    private val _errors = MutableSharedFlow<Throwable>(extraBufferCapacity = 16)
    val errors: SharedFlow<Throwable> = _errors.asSharedFlow()

    /** Loads once and caches; subsequent reads never touch the keystore. */
    fun load(): AppSettings {
        cached?.let { return it }
        val settings = try {
            val raw = prefs.getString(AppSettings.STORAGE_KEY, null)
            if (raw != null) {
                json.decodeFromString<AppSettings>(raw)
            } else {
                AppSettings.defaults
            }
        } catch (e: Throwable) {
            // A corrupt blob degrades to defaults rather than locking the user
            // out of the app. The original did the same in `fromJsonString`.
            _errors.tryEmit(e)
            AppSettings.defaults
        }
        cached = settings
        return settings
    }

    /**
     * Applies [transform] to the current settings and persists the result.
     *
     * The read-modify-write is synchronized so two concurrent writers cannot
     * drop each other's field — the original had the same concern, since the
     * onboarding flow and the backup engine both write here.
     */
    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val current = cached ?: load()
        val next = transform(current)
        try {
            prefs.edit()
                .putString(AppSettings.STORAGE_KEY, json.encodeToString(AppSettings.serializer(), next))
                .apply()
        } catch (e: Throwable) {
            _errors.tryEmit(e)
            // Cache the intended value anyway: the UI should reflect what the
            // user asked for, and a retry on the next write can still land it.
        }
        cached = next
        _changes.tryEmit(next)
        return next
    }

    /** Replaces the whole blob, bypassing read-modify-write. */
    @Synchronized
    fun set(settings: AppSettings): AppSettings = update { settings }

    /** Drops the cache so the next read goes back to storage. */
    fun invalidate() {
        cached = null
    }

    private companion object {
        const val PREF_NAME = "lumovault_settings_prefs"
    }
}

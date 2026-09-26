package com.lumovault.app.data.repository

import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.model.ThemeMode
import com.lumovault.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The theme and the backup toggles, both read from the single `app_settings` row.
 *
 * Every write here goes through [AppSettingsStore.update], which is the only writer of that row and does
 * its read-modify-write inside a transaction. That is what makes it safe for onboarding to set
 * `backup_enabled` from the setup screen while this class sets the same column from the backup screen: two
 * owners of one row is only a lost update if the second read can happen after the first write.
 */
class SettingsRepositoryImpl(private val store: AppSettingsStore) : SettingsRepository {
    override val themeMode: Flow<ThemeMode> =
        store.changes.map { settings -> ThemeMode.fromStorageKey(settings?.themeMode) }

    override suspend fun setThemeMode(mode: ThemeMode) {
        store.update { it.copy(themeMode = mode.storageKey) }
    }

    /**
     * Absent row means "never decided", so the defaults are the ones that ask nothing of the user — see
     * [BackupPreferences.Default]. Reading it as "all off" instead would let a background pass enqueue a
     * whole library on a phone whose owner never turned backup on.
     */
    override val backupPreferences: Flow<BackupPreferences> =
        store.changes.map { settings ->
            settings?.let {
                BackupPreferences(
                    automatic = it.backupEnabled,
                    wifiOnly = it.wifiOnly,
                    chargingOnly = it.chargingOnly,
                )
            } ?: BackupPreferences.Default
        }

    override suspend fun setAutomaticBackup(enabled: Boolean) {
        store.update { it.copy(backupEnabled = enabled) }
    }

    override suspend fun setBackupWifiOnly(enabled: Boolean) {
        store.update { it.copy(wifiOnly = enabled) }
    }

    override suspend fun setBackupChargingOnly(enabled: Boolean) {
        store.update { it.copy(chargingOnly = enabled) }
    }
}

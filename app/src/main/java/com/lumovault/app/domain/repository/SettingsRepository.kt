package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val themeMode: Flow<ThemeMode>

    suspend fun setThemeMode(mode: ThemeMode)

    /**
     * The background pass's permissions, live from the one settings row.
     *
     * A flow rather than a snapshot, because the pass is long-lived and a change made on screen while it is
     * waiting on a constraint has to reach it: a user who switches to mobile and turns Wi-Fi off expects
     * the queue to move, not to wait out a schedule nobody is honouring.
     */
    val backupPreferences: Flow<BackupPreferences>

    suspend fun setAutomaticBackup(enabled: Boolean)

    suspend fun setBackupWifiOnly(enabled: Boolean)

    suspend fun setBackupChargingOnly(enabled: Boolean)
}

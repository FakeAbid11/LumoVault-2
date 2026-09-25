package com.lumovault.app.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.BackupHealth
import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.telegram.TelegramAuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The backup screen: the three decisions the user makes, and the numbers that show what they did.
 *
 * Both halves are read by one screen on purpose. A toggle with no consequence beside it is a decoration —
 * "Automatic backup: on" means something only against "23 waiting, 4 failed", and putting the two on
 * separate screens is how a settings list ends up disagreeing with a status card about the same queue.
 */
class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    val health: StateFlow<BackupHealth> = container.backupHealthRepository.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), EMPTY)

    val preferences: StateFlow<BackupPreferences> = container.settingsRepository.backupPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BackupPreferences.Default)

    /**
     * Null until the first read has come back.
     *
     * Not a cosmetic choice: a seeded [BackupDiagnostics] would show `Database version 0` and `0 B` free on
     * a screen whose whole job is reporting facts, and there is no version-0 database to report.
     */
    val diagnostics: StateFlow<BackupDiagnostics?> = diagnosticsFlow(container)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    fun setAutomatic(enabled: Boolean) {
        viewModelScope.launch {
            container.settingsRepository.setAutomaticBackup(enabled)
            container.refreshAutomaticBackup()
        }
    }

    fun setWifiOnly(enabled: Boolean) = refresh { setBackupWifiOnly(enabled) }

    fun setChargingOnly(enabled: Boolean) = refresh { setBackupChargingOnly(enabled) }

    /**
     * Writes the column, then re-installs the periodic work.
     *
     * Both halves matter: constraints live on the WorkManager request rather than on the settings row, so a
     * change that only wrote the column would leave a pass scheduled under the old rules until the next app
     * start — which is exactly the delay a user who just turned on "Wi-Fi only" would see as it carrying on
     * over mobile data.
     */
    private fun refresh(write: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch {
            container.settingsRepository.write()
            container.refreshAutomaticBackup()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
        val EMPTY = BackupHealth(0, 0, 0, 0, 0, 0, 0, 0L, null, null)
    }
}

/** How the session reads, in the four words a diagnostics row has room for. */
enum class TelegramWord { Connected, WaitingForSignIn, NotConfigured, Unavailable }

/**
 * The diagnostics panel's entire input, assembled from state that already exists elsewhere.
 *
 * Nothing is measured for this screen and nothing is invented for it. [databaseVersion] is Room's own
 * declared schema version — the number a failed migration is reported against — and
 * [stagingSpaceFreeBytes] is the same figure the stager refuses to work under, which is the one storage fact
 * that explains a backup that will not start.
 *
 * No session value, phone number, api hash, chat title or message text appears in this type, and none may be
 * added later. A diagnostics screen is the place a developer is tempted to print what they are debugging,
 * and in this app the thing being debuggable belongs to somebody's Telegram account.
 */
data class BackupDiagnostics(
    val health: BackupHealth,
    val preferences: BackupPreferences,
    val telegram: TelegramWord,
    val channelAvailable: Boolean,
    val databaseVersion: Int,
    val stagingSpaceFreeBytes: Long,
)

private fun diagnosticsFlow(container: com.lumovault.app.AppContainer): Flow<BackupDiagnostics> =
    combine(
        container.backupHealthRepository.observe(),
        container.settingsRepository.backupPreferences,
        container.telegramAuthRepository.state,
    ) { health, preferences, auth ->
        BackupDiagnostics(
            health = health,
            preferences = preferences,
            telegram = when (auth) {
                is TelegramAuthState.Authenticated -> TelegramWord.Connected
                is TelegramAuthState.NotConfigured, is TelegramAuthState.Unknown -> TelegramWord.NotConfigured
                is TelegramAuthState.Failed -> TelegramWord.Unavailable
                else -> TelegramWord.WaitingForSignIn
            },
            channelAvailable = container.cloudIndexRepository.association() != null,
            databaseVersion = container.databaseVersion,
            stagingSpaceFreeBytes = container.backupFreeSpaceBytes(),
        )
    }

package com.lumovault.app.ui.backup

import androidx.annotation.StringRes
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.BackupHealth
import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.repository.SettingsRepository
import com.lumovault.app.domain.telegram.TelegramAuthState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lumovault.app.R
import com.lumovault.app.domain.model.BackupSource
import kotlinx.coroutines.flow.map

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
     * Why a queue that has work in it is not moving, or null when there is nothing to explain.
     *
     * Live for the same reason the rest of this screen is: a session can be signed out from the system, and
     * a channel is adopted the moment the Cloud tab — or now the queue itself — gets there. A remembered
     * answer would keep a line on screen that stopped being true seconds earlier.
     */
    val stopReason: StateFlow<BackupStop?> = combine(
        health,
        container.telegramAuthRepository.state,
        container.cloudIndexRepository.observeAssociation(),
    ) { live, auth, association -> backupStopReason(live.pending, auth, association != null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * Null until the first read has come back.
     *
     * Not a cosmetic choice: a seeded [BackupDiagnostics] would show `Database version 0` and `0 B` free on
     * a screen whose whole job is reporting facts, and there is no version-0 database to report.
     */
    /**
     * What the Backup & storage section says about the source, which is the same answer the folder screen
     * shows and the same two columns onboarding wrote.
     *
     * A count and a source rather than the folder list itself: the entry line has room for one sentence,
     * and the sentence a person needs is whether anything at all is chosen — `SelectedFolders` with no
     * folders is the setting that stops automatic backup, and it must not read like "everything".
     */
    val source: StateFlow<BackupSourceLine> = container.onboardingRepository.progress
        .map { BackupSourceLine(it.backupSource, it.selectedFolders.size) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BackupSourceLine(null, 0))

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
 * Why a queue with work in it is not moving.
 *
 * Each entry is a refusal the app cannot lift by itself, and each is the sentence that was missing when a
 * backup simply did not happen: the photos were queued, the pass ran, and it ended without sending — with
 * nothing anywhere saying which of these three it had ended on.
 */
enum class BackupStop(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    /** Null when there is no door to open, which is the honest answer as often as it is a dull one. */
    @StringRes val actionRes: Int?,
) {
    SignedOut(
        R.string.backup_blocked_signed_out_title,
        R.string.backup_blocked_signed_out_body,
        R.string.cloud_connect_action,
    ),
    NoChannel(
        R.string.backup_blocked_no_channel_title,
        R.string.backup_blocked_no_channel_body,
        R.string.backup_blocked_no_channel_action,
    ),
    BuildHasNoTelegram(
        R.string.backup_blocked_unbuilt_title,
        R.string.backup_blocked_unbuilt_body,
        null,
    ),
}

/**
 * Whether a waiting queue has a reason beside it, decided from the live session and the live association.
 *
 * The three refusals are the ones the upload pass can actually end on — see
 * [com.lumovault.app.domain.usecase.QueueRun] — so this is a match against what the queue really stops on,
 * not a list of guesses. A state still moving answers null on purpose: a line that is wrong on the first
 * frame is a line the user learns to ignore, and the handshake finishes in milliseconds next to a queue
 * that takes minutes.
 */
fun backupStopReason(pending: Int, auth: TelegramAuthState, channelAdopted: Boolean): BackupStop? = when {
    // Nothing is waiting, so there is nothing to explain — the health figures above stay on screen either
    // way, and an empty queue is not a complaint.
    pending == 0 -> null

    auth is TelegramAuthState.Authenticated -> if (channelAdopted) null else BackupStop.NoChannel
    auth is TelegramAuthState.NotConfigured -> BackupStop.BuildHasNoTelegram

    auth is TelegramAuthState.Unknown ||
        auth is TelegramAuthState.Initializing ||
        auth is TelegramAuthState.SendingCode ||
        auth is TelegramAuthState.VerifyingCode ||
        auth is TelegramAuthState.Authenticating -> null

    // The phone prompt, a code or password Telegram is waiting for, or a refused attempt: all of them mean
    // the same thing to a queue — nobody is signed in.
    else -> BackupStop.SignedOut
}

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
            // This transform runs wherever the flow is collected — the hub's viewModelScope, i.e. Main —
            // and these two are not Room-scheduled queries: reading the version opens the database file
            // and reading the free space stats the filesystem. Both go to the IO dispatcher, or every
            // health/preference/auth emission pays for them synchronously on the UI thread.
            databaseVersion = withContext(Dispatchers.IO) { container.databaseVersion },
            stagingSpaceFreeBytes = withContext(Dispatchers.IO) { container.backupFreeSpaceBytes() },
        )
    }

/** The hub's one-line summary of the backup source. */
data class BackupSourceLine(val source: BackupSource?, val folderCount: Int) {
    @get:StringRes
    val labelRes: Int
        get() = when {
            source == null -> R.string.backup_folders_entry_unanswered
            source == BackupSource.AllMedia -> R.string.backup_folders_entry_all
            folderCount == 0 -> R.string.backup_folders_entry_none
            else -> R.plurals.backup_folders_selected
        }
}

/**
 * The line as words, in the one place the four cases are decided.
 *
 * Both the hub and the folder screen show this, and they showed it differently: each had its own `if`, and
 * the folder screen's knew only "everything" and "not chosen yet" — so a person with three folders selected
 * was told, on the screen that lists folders, that nothing had been chosen. One helper, two callers, one
 * thing that can be wrong.
 */
@Composable
fun BackupSourceLine.label(): String =
    if (labelRes == R.plurals.backup_folders_selected) {
        pluralStringResource(labelRes, folderCount, folderCount)
    } else {
        stringResource(labelRes)
    }

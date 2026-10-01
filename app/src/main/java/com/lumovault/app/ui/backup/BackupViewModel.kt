package com.lumovault.app.ui.backup

import androidx.annotation.StringRes
import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.data.backup.SendHold
import com.lumovault.app.data.backup.sendHoldFor
import com.lumovault.app.data.backup.toAutomaticWorkRequest
import com.lumovault.app.domain.backup.BackupFailureItem
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
import com.lumovault.app.domain.model.canRunAutomatic
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
     * What the user's own Wi-Fi and charging choices are holding the unattended send back by, or `None`.
     *
     * Declared above [stopReason] because the class body runs top to bottom and that flow reads this one.
     *
     * The blocked half comes from WorkManager and only the naming half from the settings row, joined here so
     * the two cannot disagree about which of them was consulted — see [sendHoldFor]. `None` is the seed rather
     * than a guess: the first frame is one where nothing has been asked yet, and [stopReason] treats "no hold"
     * exactly as it treated every frame before this flow existed.
     */
    private val sendHold: StateFlow<SendHold> = combine(
        preferences,
        container.backupScheduler.sendHeldByConstraints(),
    ) { current, blocked -> sendHoldFor(current.toAutomaticWorkRequest(), blocked) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SendHold.None)

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
        sendHold,
    ) { live, auth, association, hold ->
        backupStopReason(live.pending, auth, association != null, hold)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    /**
     * The items the queue gave up on, newest first, for the diagnostics list.
     *
     * Its own flow rather than another field on [BackupDiagnostics]: that type's contract is about what it
     * never carries, and a photo's file name is a new kind of content for it. The list is bounded, and the
     * screen says so when it truncates — an incomplete list that looks complete is worse than a short one.
     */
    val failedItems: StateFlow<List<BackupFailureItem>> =
        container.backupQueueRepository.observeFailed(FAILED_LIST_LIMIT)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

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
            // The switch is only drawn when it can do something; this is the same rule rather than a second
            // opinion of it, so a stale frame cannot write a column that makes every pass a no-op.
            if (enabled && !source.value.canEnableAutomatic) return@launch
            container.settingsRepository.setAutomaticBackup(enabled)
            container.refreshAutomaticBackup()
        }
    }

    fun setWifiOnly(enabled: Boolean) = refresh { setBackupWifiOnly(enabled) }

    fun setChargingOnly(enabled: Boolean) = refresh { setBackupChargingOnly(enabled) }

    /**
     * Writes the column, then re-decides the schedule.
     *
     * The re-decision is what [setAutomatic] needs: `automatic` is the only setting the periodic pass reads, so
     * WorkManager cannot notice it change and the request either exists or does not until something says so.
     * For these two toggles it is a formality rather than a necessity — the scan carries no constraints, and
     * the send reads the row afresh each time it is enqueued — and it is kept so that every
     * write to the backup settings leaves by the same reconciliation rather than by three of them that each
     * remember a different subset of what depends on what.
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

        /**
         * How many failures the diagnostics list will show.
         *
         * A phone that backed up over a metered connection all week can hold thousands of failed rows, and
         * the screen is a place to start reading, not an export. The count beside it is the whole number.
         */
        const val FAILED_LIST_LIMIT = 20
    }
}

/**
 * How the session reads, in the four words a diagnostics row has room for.
 *
 * The word and its label belong together: the diagnostics panel and the settings account screen describe
 * the same live state, and two `when`s deciding it separately is how they would start disagreeing about
 * whether the user is signed in.
 */
enum class TelegramWord(@StringRes val labelRes: Int) {
    Connected(R.string.diag_telegram_connected),
    WaitingForSignIn(R.string.diag_telegram_waiting),
    NotConfigured(R.string.diag_telegram_not_configured),
    Unavailable(R.string.diag_telegram_unavailable),
    ;

    companion object {
        fun of(state: TelegramAuthState): TelegramWord = when (state) {
            is TelegramAuthState.Authenticated -> Connected
            is TelegramAuthState.NotConfigured, is TelegramAuthState.Unknown -> NotConfigured
            is TelegramAuthState.Failed -> Unavailable
            else -> WaitingForSignIn
        }
    }
}

/**
 * Why a queue with work in it is not moving.
 *
 * Each entry is a condition the app cannot lift by itself, and each is the sentence that was missing when a
 * backup simply did not happen: the photos were queued, the pass ran, and it ended without sending — with
 * nothing anywhere saying which of these it had ended on.
 *
 * The last three are not endings but waits the user asked for, and the distinction is the whole reason
 * [urgent] exists: a red card drawn over a checkbox is a bug wearing a warning, and "signed out, go fix it"
 * and "this sends when the phone reaches Wi-Fi" have to look different to be worth reading.
 */
enum class BackupStop(
    @StringRes val titleRes: Int,
    @StringRes val bodyRes: Int,
    /** Null when there is no door to open, which is the honest answer as often as it is a dull one. */
    @StringRes val actionRes: Int?,
    /** False when the queue is waiting on a preference the user set, rather than stuck on something broken. */
    val urgent: Boolean = true,
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
    WaitingForWifi(
        R.string.backup_blocked_wifi_title,
        R.string.backup_blocked_wifi_body,
        null,
        urgent = false,
    ),
    WaitingForCharger(
        R.string.backup_blocked_charger_title,
        R.string.backup_blocked_charger_body,
        null,
        urgent = false,
    ),
    WaitingForWifiAndCharger(
        R.string.backup_blocked_wifi_charger_title,
        R.string.backup_blocked_wifi_charger_body,
        null,
        urgent = false,
    ),
}

/**
 * Whether a waiting queue has a reason beside it, decided from the live session, the live association, and
 * what WorkManager reports about the send.
 *
 * The first three refusals are the ones the upload pass can actually end on — see
 * [com.lumovault.app.domain.usecase.QueueRun] — so this is a match against what the queue really stops on, not
 * a list of guesses. The three waits are the reason that match stopped being complete: the pass that never
 * starts is not in [com.lumovault.app.domain.usecase.QueueRun] at all, and an authenticated session with an
 * adopted channel answered null for it, which is precisely what a queue held behind a charger looks like.
 *
 * A session problem still outranks a preference. It is the one a person has to act on, and it would still be
 * true the second the phone is put on its charger.
 */
internal fun backupStopReason(
    pending: Int,
    auth: TelegramAuthState,
    channelAdopted: Boolean,
    hold: SendHold = SendHold.None,
): BackupStop? = when {
    // Nothing is waiting, so there is nothing to explain — the health figures above stay on screen either
    // way, and an empty queue is not a complaint.
    pending == 0 -> null

    auth is TelegramAuthState.Authenticated ->
        if (!channelAdopted) BackupStop.NoChannel else hold.toStop()

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
 * The words for a hold, or null when there is none.
 *
 * `None` has no card because the absence of a hold is the normal state of a working queue, and a screen that
 * explains normality has stopped reporting anything.
 */
private fun SendHold.toStop(): BackupStop? = when (this) {
    SendHold.None -> null
    SendHold.WaitingForUnmetered -> BackupStop.WaitingForWifi
    SendHold.WaitingForCharger -> BackupStop.WaitingForCharger
    SendHold.WaitingForBoth -> BackupStop.WaitingForWifiAndCharger
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
            telegram = TelegramWord.of(auth),
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
    /**
     * Whether "Automatic backup" is a switch on this answer or a door to the folder screen.
     *
     * Turning it on without something to back up writes the column, installs the schedule and queues
     * nothing — a phone that then scans its library on a period, forever, with no reason anywhere for the
     * photos not to move.
     */
    val canEnableAutomatic: Boolean
        get() = source.canRunAutomatic(folderCount)

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

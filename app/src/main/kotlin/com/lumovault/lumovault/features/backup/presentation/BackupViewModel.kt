package com.lumovault.lumovault.features.backup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.lumovault.lumovault.core.auth.AuthService
import com.lumovault.lumovault.core.auth.AuthState
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaStatus
import com.lumovault.lumovault.features.backup.data.work.BackupScheduler
import com.lumovault.lumovault.features.backup.data.work.BackupWorker
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Item counts grouped by upload status, derived from the Room media table.
 *
 * The dashboard and storage-stats screens in the Flutter app both read the
 * same `BackupStats` (total / backed-up / pending / failed / bytes); this is
 * the Kotlin equivalent, computed from [MediaDao.timelineFlow] rather than new
 * DAO queries — the full table scan is already materialised for the timeline,
 * so per-status counts are a trivial `groupBy` over it.
 */
data class BackupCounts(
    val total: Int = 0,
    val uploaded: Int = 0,
    val pending: Int = 0,
    val failed: Int = 0,
    val uploading: Int = 0,
    val totalBytes: Long = 0L,
    val backedUpBytes: Long = 0L,
)

/**
 * State for the backup + storage screens.
 *
 * Ported from backup_dashboard_screen.dart / storage_stats_screen.dart and
 * their `backupStatsProvider`, now wired to the live engine: Start enqueues a
 * one-shot [BackupWorker] (gated on Telegram sign-in), and per-status counts
 * update live from the Room media table the engine writes to.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    /** Application context, for observing WorkManager state. */
    @ApplicationContext private val application: Context,
    /** Reactive settings, provided by SettingsModule. */
    val settings: StateFlow<AppSettings>,
    private val settingsRepository: SettingsRepository,
    private val scheduler: BackupScheduler,
    private val engine: BackupEngine,
    authService: AuthService,
    mediaDao: MediaDao,
) : ViewModel() {

    /** Backup needs a signed-in Telegram session; the gate mirrors restore's. */
    val canBackup: StateFlow<Boolean> = authService.state
        .map { it == AuthState.authenticated }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = authService.currentState == AuthState.authenticated,
        )

    /** True while a WorkManager-driven engine pass is pending or running. */
    val engineBusy: StateFlow<Boolean> = WorkManager.getInstance(application)
        .getWorkInfosForUniqueWorkFlow(BackupWorker.ONESHOT_WORK)
        .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Manual start: requests a manual pass (overriding the auto-backup toggle)
     * and enqueues the one-shot worker. Progress surfaces through the media
     * table, which the counts flow already observes.
     */
    fun startBackup() {
        engine.requestManualRun()
        scheduler.runNow()
    }

    /** Per-status counts and byte totals over the visible (non-trashed) library. */
    val counts: StateFlow<BackupCounts> = mediaDao.timelineFlow()
        .map { items ->
            BackupCounts(
                total = items.size,
                uploaded = items.count { it.status == MediaStatus.uploaded },
                pending = items.count { it.status == MediaStatus.pending },
                failed = items.count { it.status == MediaStatus.failed },
                uploading = items.count { it.status == MediaStatus.uploading },
                totalBytes = items.sumOf { it.fileSize },
                backedUpBytes = items
                    .filter { it.status == MediaStatus.uploaded }
                    .sumOf { it.fileSize },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupCounts())

    /**
     * Applies [transform] to the current settings and persists the result.
     * Thin pass-through to [SettingsRepository.update] so the settings screen
     * stays the single writer path, as in the Flutter `SettingsNotifier`.
     */
    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepository.update(transform)
    }
}

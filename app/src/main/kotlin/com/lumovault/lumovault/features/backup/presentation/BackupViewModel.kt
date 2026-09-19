package com.lumovault.lumovault.features.backup.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaStatus
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * their `backupStatsProvider`. The upload *engine* is not part of this build,
 * so there is deliberately no start/pause/retry here — the screens render the
 * controls disabled with an explanatory caption instead of wiring dead
 * callbacks (a control that looks finished but does nothing is worse than a
 * disabled one).
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    /** Reactive settings, provided by SettingsModule. */
    val settings: StateFlow<AppSettings>,
    private val settingsRepository: SettingsRepository,
    mediaDao: MediaDao,
) : ViewModel() {

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

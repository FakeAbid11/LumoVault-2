package com.lumovault.app.ui.screens.photos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.groupByDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Screen state for the local library.
 *
 * It owns three things the UI must not: the MediaStore scan, the Room queries, and the decision
 * about which of the [PhotosUiState] cases applies. Reading permissions also happens here rather
 * than in a composable, because the answer changes when the user revokes access from system
 * settings while the app is backgrounded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val access = MutableStateFlow(MediaAccessStatus.Unknown)
    private val scanning = MutableStateFlow(false)
    private val scanFailed = MutableStateFlow(false)

    /** Grows as the user reaches the end of the timeline; see [WINDOW_START] for why. */
    private val loadedLimit = MutableStateFlow(WINDOW_START)

    private val items = loadedLimit
        .flatMapLatest { limit -> container.mediaRepository.observeWindow(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val totalCount = container.mediaRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /** The ids the user has marked; the action bar and the cell ticks both read this. */
    val selected: StateFlow<Set<Long>> = selection.asStateFlow()

    /**
     * Queue state for the items currently on screen, plus the counts for the progress line.
     *
     * Scoped to the loaded window rather than to the whole queue: a library can hold tens of thousands
     * of items and only a few dozen are being drawn, so the app asks Room about what it is about to
     * show. Reloading on every window change is cheap — one indexed `IN` query — and it is what lets the
     * glyph on a cell come from the same rows the worker writes.
     */
    val backup: StateFlow<BackupOverview> = items
        .map { media -> media.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids ->
            combine(
                container.backupQueueRepository.observeStatesFor(ids),
                container.backupQueueRepository.observeSummary(),
            ) { states, summary -> BackupOverview(states, summary) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), BackupOverview())

    /**
     * Which of the visible items are favourited.
     *
     * A flow of its own rather than a sixth input to [uiState] for the same reason [backup] is: `combine`
     * types five flows and quietly degrades to `Array<Any>` at six, which would break the derivation in a
     * way the compiler cannot point at. Scoped to the loaded window for the same cost reason — the grid
     * marks what it draws, not what the device holds.
     */
    val favorites: StateFlow<Set<Long>> = items
        .map { media -> media.map { it.id } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> container.mediaOrganizationRepository.observeFavoritesWithin(ids) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptySet())

    val uiState: StateFlow<PhotosUiState> = combine(
        access,
        items,
        totalCount,
        scanning,
        scanFailed,
    ) { accessState, media, total, isScanning, failed ->
        derivePhotosState(
            access = accessState,
            items = media,
            totalCount = total,
            isScanning = isScanning,
            scanFailed = failed,
            days = media.groupByDay(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), PhotosUiState.CheckingAccess)

    /** Rows the current scan found, for the "1,248 items found" line. Updated as it proceeds. */
    private val _scanProgress = MutableStateFlow(0)
    val scanProgress: StateFlow<Int> = _scanProgress

    init {
        refreshAccess()
        viewModelScope.launch { syncIfNeeded() }

        // The count already in the window is the only progress figure that is real; percentage
        // would be a guess, so the UI shows an indeterminate indicator plus this number.
        //
        // The subscription exists only while a scan is running: an unconditional collector on `items`
        // would keep the Room window query hot for this ViewModel's whole life — including while the
        // screen sits in the back stack — which is exactly what the WhileSubscribed policy everywhere
        // else in this class exists to prevent.
        scanning
            .flatMapLatest { active -> if (active) items else flow { } }
            .onEach { media -> _scanProgress.value = media.size }
            .launchIn(viewModelScope)
    }

    /** Called when the screen resumes: the grant can change while LumoVault is backgrounded. */
    fun resume() {
        refreshAccess()
        viewModelScope.launch { syncIfNeeded() }
    }

    /** The permissions this Android version asks for; the screen owns the launcher, not the scan. */
    fun mediaPermissionsToRequest(): List<String> =
        container.permissionRepository.mediaPermissionsToRequest()

    /** Re-reads the grant after a permission dialog, without forcing a rescan. */
    fun refreshAccess() {
        viewModelScope.launch {
            access.value = container.permissionRepository.mediaStatus()
        }
    }

    /** Explicit rescan, used by pull-to-refresh: always re-reads MediaStore. */
    fun refresh() {
        refreshAccess()
        viewModelScope.launch { sync() }
    }

    fun loadMore() {
        loadedLimit.value = loadedLimit.value + WINDOW_STEP
    }

    /**
     * Toggles a cell, but only once a selection already exists.
     *
     * Selection starts on a long press because a tap belongs to the viewer: opening the photograph a person
     * touched is what they asked for, and a mode that began on a single tap would make the library feel
     * broken. After that first long press, tapping adds — which is what makes a ten-item selection
     * possible without a long-press per item.
     *
     * The screen decides which of the two a tap means; this class only knows about the selection.
     */
    fun onCellClick(mediaId: Long) {
        if (selection.value.isNotEmpty()) toggle(mediaId)
    }

    fun onCellLongClick(mediaId: Long) = toggle(mediaId)

    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * Queues the selection and asks for a pass.
     *
     * Enqueueing is a database write and starting the work is a separate call, so a process that dies
     * in between loses nothing: the rows are already there, and the next launch's queue is drained by
     * whatever schedules it.
     */
    fun backUpSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return

        viewModelScope.launch {
            container.backupQueueRepository.enqueue(ids)
            selection.value = emptySet()
            container.backupScheduler.start()
        }
    }

    /**
     * Favourites or un-favourites the selection, and keeps the selection so the second tap can undo it.
     *
     * No upload is queued from here, and none can be: this writes to the organisation table and touches
     * nothing the backup engine reads. A photo can be favourite-and-backed-up or favourite-and-not, and
     * the two marks live in different tables on purpose.
     */
    fun setFavoriteSelected(favorite: Boolean) {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch { container.mediaOrganizationRepository.setFavorite(ids, favorite) }
    }

    /**
     * Archives the selection, which makes them leave this screen — the timeline query filters archived
     * items out in the database, so the grid refills from Room rather than this class forgetting rows.
     * That is also why the selection is cleared: the ids are still selected, but on items the user can no
     * longer see, and an action bar counting invisible rows would be a bug dressed as a feature.
     */
    fun archiveSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.mediaOrganizationRepository.setArchived(ids, true)
            selection.value = emptySet()
        }
    }

    /** Moves the selection to Trash. The files and the backups are untouched; see [archiveSelected]. */
    fun moveToTrashSelected() {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            container.mediaOrganizationRepository.moveToTrash(ids)
            selection.value = emptySet()
        }
    }

    /** Withdraws everything still waiting. An upload already in flight is left to finish. */
    fun cancelPending() {
        viewModelScope.launch { container.runBackupQueue.cancelPending() }
    }

    fun retryFailed() {
        viewModelScope.launch {
            if (container.runBackupQueue.retryFailed() > 0) container.backupScheduler.start()
        }
    }

    private fun toggle(mediaId: Long) {
        selection.update { current -> if (mediaId in current) current - mediaId else current + mediaId }
    }

    private suspend fun syncIfNeeded() {
        if (totalCount.value > 0 && !scanning.value) return
        sync()
    }

    private suspend fun sync() {
        if (scanning.value) return
        if (!access.value.allowsScanning) return

        scanning.value = true
        scanFailed.value = false
        _scanProgress.value = 0
        try {
            container.mediaRepository.sync()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Only the exception type is logged: a MediaStore or SQLite message can quote a file
            // path, and a filename is personal data.
            android.util.Log.w(TAG, "media scan failed: ${error.javaClass.simpleName}")
            scanFailed.value = true
        } finally {
            scanning.value = false
        }
    }

    private companion object {
        const val TAG = "LumoVaultMedia"

        /**
         * The timeline is loaded in a widening window rather than all at once: a library can hold
         * tens of thousands of rows, and grouping is done over what is loaded, so scrolling extends
         * the same query instead of materialising everything.
         */
        const val WINDOW_START = 300
        const val WINDOW_STEP = 300
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

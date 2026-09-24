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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
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
        items
            .onEach { media -> if (scanning.value) _scanProgress.value = media.size }
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

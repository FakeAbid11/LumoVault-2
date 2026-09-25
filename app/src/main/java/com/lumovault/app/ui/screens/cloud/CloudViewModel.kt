package com.lumovault.app.ui.screens.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.CloudMedia
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
 * Screen state for the cloud library.
 *
 * The nine-step start-up machine is not here: [com.lumovault.app.domain.usecase.SynchronizeCloudUseCase]
 * owns it, because it spans Telegram and Room and has to be testable without a ViewModel. This class
 * reads the index, forwards intent, and picks the [CloudUiState] case — no TDLib request and no SQL
 * below this point.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CloudViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    /** Grows as the user reaches the end of the timeline; see [WINDOW_START]. */
    private val loadedLimit = MutableStateFlow(WINDOW_START)

    private val localMatches = MutableStateFlow(emptySet<Long>())

    /**
     * Re-entrancy guard only, deliberately not observable state.
     *
     * A sync in flight is already expressed by [com.lumovault.app.domain.telegram.CloudInitState.Scanning],
     * so publishing a second flow that says the same thing would create a pair of values that can
     * disagree — and Kotlin's typed `combine` only covers five flows anyway, which is what made the
     * six-way version resolve through the vararg overload and lose its types.
     */
    private var syncing = false

    private val items: StateFlow<List<CloudMedia>> = loadedLimit
        .flatMapLatest { limit -> container.cloudIndexRepository.observeWindow(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val totalCount = container.cloudIndexRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val counts = container.cloudIndexRepository.observeTypeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    val uiState: StateFlow<CloudUiState> = combine(
        container.cloudSync.state,
        items,
        totalCount,
        counts,
        localMatches,
    ) { init, media, total, typeCounts, backedUp ->
        deriveCloudState(
            init = init,
            items = media,
            totalCount = total,
            counts = typeCounts,
            localMatches = backedUp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CloudUiState.Idle)

    init {
        // Which of these remote items also live on the device: one batched query over the loaded
        // window, never a MediaStore or database round-trip per cell.
        items
            .onEach { media -> localMatches.value = container.localPresenceLookup.backedUp(media) }
            .launchIn(viewModelScope)

        synchronize()
    }

    fun resume() {
        synchronize()
    }

    /** Pull-to-refresh, running the same use case the start-up path uses. */
    fun refresh() {
        synchronize()
    }

    fun loadMore() {
        loadedLimit.value = loadedLimit.value + WINDOW_STEP
    }

    /**
     * Local path for one cell's *thumbnail*, or null when no preview can be shown.
     *
     * Only `previewRemoteFileId` is ever passed down. Nothing in this call chain can request the
     * original file, which is what keeps the grid's rendering inside PRD section 25.
     */
    suspend fun previewPath(item: CloudMedia): String? =
        container.telegramPreviewRepository.localPathFor(item.previewRemoteFileId)

    private fun synchronize() {
        if (syncing) return
        syncing = true

        viewModelScope.launch {
            try {
                // Repeated calls are safe, and needed: the Cloud tab can be opened without onboarding
                // having touched TDLib, and a chat request before a session is meaningless.
                container.telegramAuthRepository.connect()
                val adopted = container.cloudSync.synchronize()
                if (adopted != null) {
                    // The index has just changed, which is the one moment recognition is certain to have
                    // something new to match: after a reinstall this is what turns 1,000 channel messages
                    // into 1,000 backed-up photos without a single upload. A pass that finds nothing to do
                    // costs one query, and the queue's own rows are drained by the same call.
                    container.backupScheduler.start()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Class name only — a TDLib error string can carry a chat title.
                android.util.Log.w(TAG, "cloud sync failed: ${error.javaClass.simpleName}")
            } finally {
                syncing = false
            }
        }
    }

    private companion object {
        const val TAG = "LumoVaultCloud"

        /** Same widening-window approach as the local timeline, for the same reason. */
        const val WINDOW_START = 300
        const val WINDOW_STEP = 300
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

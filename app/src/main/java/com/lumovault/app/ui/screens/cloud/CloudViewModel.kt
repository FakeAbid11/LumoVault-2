package com.lumovault.app.ui.screens.cloud

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.AppContainer
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.R
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.model.ShareableMedia
import com.lumovault.app.domain.restore.CloudRestoreTarget
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.ui.components.AppMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    /**
     * Re-entrancy guard only, deliberately not observable state.
     *
     * A sync in flight is already expressed by [com.lumovault.app.domain.telegram.CloudInitState.Scanning],
     * so publishing a second flow that says the same thing would create a pair of values that can
     * disagree — and Kotlin's typed `combine` only covers five flows anyway, which is what made the
     * six-way version resolve through the vararg overload and lose its types.
     */
    private var syncing = false

    /**
     * An exception escaped the sync itself — thrown before the state machine ever left `Idle`, say.
     * The machine cannot report what it never reached, so this flag is the only record that a pass was
     * attempted and did not answer. It exists because the alternative was a Cloud tab that stayed
     * blank forever with the failure written only to the log.
     */
    private val syncFailed = MutableStateFlow(false)

    /**
     * Which message ids the user has chosen. Ids rather than rows: the selection outlives any
     * window, and [CloudSelection] holds the only two rules it follows — toggle, and an unwindowed
     * select-all.
     */
    private val selection = CloudSelection { container.cloudIndexRepository.allIds() }

    /** The ids chosen — the strip's count and the cells' ticks read this one set. */
    val selected: StateFlow<Set<Long>> = selection.selected

    /**
     * One-shot feedback for actions taken from this screen, as the strings to show.
     *
     * Failures and counts only, buffered the way the other grids buffer theirs: a burst of failures
     * is better reported newest-first than by stalling the action that reported them, and a line
     * waiting with no collector while the screen is away is dropped rather than shown somewhere
     * else later.
     */
    private val _messages = MutableSharedFlow<AppMessage>(
        extraBufferCapacity = MESSAGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<AppMessage> = _messages.asSharedFlow()

    private val items: StateFlow<List<CloudMedia>> = loadedLimit
        .flatMapLatest { limit -> container.cloudIndexRepository.observeWindow(limit) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    private val totalCount = container.cloudIndexRepository.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), 0)

    private val counts = container.cloudIndexRepository.observeTypeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyList())

    /**
     * Room's invalidation signal for the local index. Its value is not read — subscribing is the point:
     * a restore that lands writes a `media` row, which re-runs the presence lookup, which flips this
     * item's badge from "In cloud" to "On device" within a frame instead of at the next resume. Before
     * this, every input to [uiState] was cloud-side and the badge sat stale over a file the user could
     * already open in the gallery.
     */
    private val localChanges = container.mediaRepository.observeCount()

    // The typed `combine` answers five flows and quietly degrades to `Array<Any?>` at six, so the six
    // inputs are grouped into two typed fives threes and rejoined. The alternative — vararg combine — is
    // the exact overload the re-entrancy comment above this class exists to warn about.
    val uiState: StateFlow<CloudUiState> = combine(
        combine(container.cloudSync.state, syncFailed) { init, failed -> init to failed },
        combine(items, totalCount, counts, localChanges) { media, total, typeCounts, _ ->
            CloudIndexWindow(media, total, typeCounts)
        },
    ) { (init, failed), window ->
        val derived = deriveCloudState(
            init = init,
            items = window.items,
            totalCount = window.total,
            counts = window.typeCounts,
            // Which of these remote items also live on the device: one batched query over the loaded
            // window, never a MediaStore or database round-trip per cell. Computed inside this transform
            // rather than by a side collector on `items` — a side collector subscribes for the ViewModel's
            // whole life and keeps the Room window query running while this screen sits in the back
            // stack, defeating the WhileSubscribed policy below. Here the lookup runs exactly while the
            // screen is watching. Room's suspend queries execute on its own executor, not on this
            // collector's thread.
            localMatches = if (window.items.isEmpty()) {
                emptySet()
            } else {
                container.localPresenceLookup.backedUp(window.items)
            },
        )
        // "Nothing has happened yet" plus "the one attempt threw" is a failure with a retry, not a
        // blank tab. Any other derived state already says something truer than this flag could.
        if (failed && derived is CloudUiState.Idle) {
            CloudUiState.Failed(CloudFailure(CloudFailure.Kind.RequestFailed))
        } else {
            derived
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), CloudUiState.Idle)

    /** Three cloud-side reads that always change together; a private carrier, not a domain type. */
    private data class CloudIndexWindow(
        val items: List<CloudMedia>,
        val total: Int,
        val typeCounts: List<CloudTypeCount>,
    )

    /**
     * The one message the item sheet is open on, which is what the job row below is keyed by.
     *
     * A `MutableStateFlow` rather than a parameter on the composable because the sheet can be closed by a
     * back gesture, a tap outside, or the process going away, and each of those must stop the observation
     * rather than leave a flow collecting for a screen that is gone.
     */
    private val restoreTarget = MutableStateFlow<CloudMedia?>(null)

    /** Live and finished restores for the loaded window, keyed by message id, for a badge on a cell. */
    val restoreJobs: StateFlow<Map<Long, RestoreJob>> = items
        .flatMapLatest { media ->
            val chatId = media.firstOrNull()?.chatId ?: 0L
            val ids = media.map { it.messageId }
            // Empty ids would reach Room as `IN ()`, which is not SQL; "nothing loaded" is not a question.
            if (chatId == 0L || ids.isEmpty()) flowOf(emptyMap())
            else container.restoreRepository.observeForMessages(chatId, ids)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), emptyMap())

    /** The sheet's own view of one download, so its bar and its buttons read the row, not a memory. */
    val restoreJob: StateFlow<RestoreJob?> = restoreTarget
        .flatMapLatest { item ->
            if (item == null) flowOf(null) else container.restoreRepository.observeJob(item.chatId, item.messageId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), null)

    init {
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

    /** A tap toggles once a selection exists; a long press starts one. The screen decides which. */
    fun toggleSelection(messageId: Long) = selection.toggle(messageId)

    fun clearSelection() = selection.clear()

    /**
     * Every message id the index holds — not the pages scrolled so far. The count the strip prints
     * is the count the actions act on, which is the same rule the local timeline's select-all obeys.
     */
    fun selectAll() = launchWrite("cloud select all", failureMessage = R.string.feedback_action_failed) {
        selection.selectAll()
        null
    }

    /**
     * Hands the selection to Android's share sheet through the app's one share utility.
     *
     * Only local copies travel. A cloud item this device does not hold has no bytes to share, and
     * sending its thumbnail would give the receiver something it would read as the photo — so how
     * many were in that state is said out loud instead of quietly skipped, and when *nothing* can be
     * shared no sheet opens: [onShare] fires only for a share that will really happen.
     */
    fun shareSelected(onShare: (List<ShareableMedia>) -> Unit) =
        launchWrite("cloud share", failureMessage = R.string.feedback_action_failed) {
            val ids = selection.selected.value
            if (ids.isEmpty()) return@launchWrite null

            val items = container.cloudIndexRepository.itemsFor(ids)
            val matched = container.localPresenceLookup.localMediaIds(items)
            val rows = container.localPresenceLookup.rowsForMedia(matched.values)
            val shareables = rows
                .filter { row -> row.contentUri.isNotBlank() }
                .map { row -> ShareableMedia(uri = row.contentUri, mimeType = row.mimeType) }
                .distinctBy { it.uri }

            val offDevice = items.size - matched.size
            if (offDevice > 0) {
                _messages.tryEmit(AppMessage(R.plurals.cloud_share_off_device_count, offDevice))
            }
            when {
                shareables.isNotEmpty() -> onShare(shareables)
                // The off-device line above already said why no sheet opened; asking again what
                // went wrong when nothing went wrong would be noise.
                offDevice == 0 -> _messages.tryEmit(AppMessage(R.string.share_nothing))
            }
            null
        }

    /**
     * Hands the selection to the restore engine the item sheet already uses — one path, one job table,
     * one progress bar — as a single serial pass rather than one launch per item, so a select-all cannot
     * put thousands of transfers in flight at once. Duplicates and items already resident are refused by
     * that engine itself, so starting the whole selection is safe; the count reported is the count of
     * records handed to it, read after that hand-off rather than before, and it says *requested* because
     * that is the only claim the hand-off can make — which of them the engine then started, or refused,
     * is decided below this point and shown on each cell's own job row.
     */
    fun restoreSelected() =
        launchWrite("cloud restore selection", failureMessage = R.string.feedback_action_failed) {
            val ids = selection.selected.value
            if (ids.isEmpty()) return@launchWrite null

            val items = container.cloudIndexRepository.itemsFor(ids)
            if (items.isEmpty()) {
                null
            } else {
                container.restoreCloudMedia.startAll(items.map(CloudRestoreTarget::from))
                AppMessage(R.plurals.cloud_restore_requested_count, items.size)
            }
        }

    /**
     * The device row behind one cloud item, for opening it in the shared viewer — or null when the
     * device holds no copy. Null is the honest answer for a cloud-only item, and it is what keeps
     * the screen offering the details-and-download sheet for those instead of a viewer with no
     * bytes to draw.
     */
    suspend fun localMediaIdFor(item: CloudMedia): Long? =
        container.localPresenceLookup.localMediaIds(listOf(item))[item.messageId]

    /** The sheet opened on one record; null when it closed. See [restoreTarget]. */
    fun focusing(item: CloudMedia?) {
        restoreTarget.value = item
    }

    /**
     * Asks for the original. PRD section 25's rule survives: this is reached only from a tap on Download,
     * never while the grid renders, and the download engine is Phase 9's — nothing here re-implements it.
     */
    fun restore(item: CloudMedia) {
        container.restoreCloudMedia.start(CloudRestoreTarget.from(item))
    }

    /**
     * Stops a transfer the user started.
     *
     * Refusing silently when there is nothing running is deliberate: after a restart the row reads as
     * interrupted and the button offers a retry, so a cancel that had no job would otherwise claim to have
     * stopped something that had already stopped on its own.
     */
    fun cancelRestore(item: CloudMedia) {
        container.restoreCloudMedia.cancel(item.chatId, item.messageId)
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
                syncFailed.value = false
                // Repeated calls are safe, and needed: the Cloud tab can be opened without onboarding
                // having touched TDLib, and a chat request before a session is meaningless.
                container.telegramAuthRepository.connect()
                // The handshake is asynchronous, and "is authenticated?" reads live state — so on this
                // tab's very first sync of a process, the answer used to arrive before TDLib had
                // finished starting and read as "signed out": the offline pill over a healthy cached
                // library, or the connect dialog over a session that was fine. Waiting for a settled
                // answer costs nothing once the session exists (awaitReady returns immediately) and
                // bounds the wait at the same ceiling the queue's unattended pass uses; when it times
                // out or reports no account, the synchronize below still derives the honest state.
                container.telegramAuthRepository.awaitReady(AppContainer.SESSION_HANDSHAKE_MILLIS)
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
                syncFailed.value = true
            } finally {
                syncing = false
            }
        }
    }

    /**
     * Runs one intent the grid has already committed to, and stays honest when it throws.
     *
     * Cancellation is rethrown; everything else is one class-name log line — a SQLite or TDLib
     * message can quote a path or a chat title — plus [failureMessage] for the screen, because an
     * action that failed and said nothing is indistinguishable from one that did nothing. [block]
     * returns the line to show once it has fully completed: a count may never claim work that did
     * not finish, which is why it is the block's answer rather than a value captured up front.
     */
    private fun launchWrite(
        description: String,
        failureMessage: Int? = null,
        block: suspend () -> AppMessage?,
    ) {
        viewModelScope.launch {
            try {
                block()?.let { message -> _messages.tryEmit(message) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                android.util.Log.w(TAG, "$description failed: ${error.javaClass.simpleName}")
                failureMessage?.let { message -> _messages.tryEmit(AppMessage(message)) }
            }
        }
    }

    private companion object {
        const val TAG = "LumoVaultCloud"

        /** How many feedback lines wait for a screen that is still showing the previous one. */
        const val MESSAGE_BUFFER = 8

        /** Same widening-window approach as the local timeline, for the same reason. */
        const val WINDOW_START = 300
        const val WINDOW_STEP = 300
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

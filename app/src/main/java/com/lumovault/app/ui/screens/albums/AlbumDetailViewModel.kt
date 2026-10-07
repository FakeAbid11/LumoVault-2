package com.lumovault.app.ui.screens.albums

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.R
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.ShareableMedia
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.Album
import com.lumovault.app.ui.components.AppMessage
import com.lumovault.app.ui.navigation.AlbumTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One album's contents, and what can be done to them.
 *
 * [userAlbum] is null exactly when this is a system album, and the screen reads that to decide which
 * actions to offer: a system album's membership is a consequence of what its files are, so "remove from
 * album" has no meaning there and offering it would promise an edit that nothing could undo.
 */
data class AlbumDetailUiState(
    val userAlbum: Album? = null,
    val systemAlbum: SystemAlbum? = null,
    /** Null until Room's first answer: an empty album and an unqueried one mean opposite things. */
    val items: List<Media>? = null,
    val favoriteIds: Set<Long> = emptySet(),
    val memberIds: Set<Long> = emptySet(),
    val selection: Set<Long> = emptySet(),
    /** The folder's own name, when this is a device folder rather than an album of either kind. */
    val folderName: String? = null,
) {
    val isUserAlbum: Boolean get() = userAlbum != null

    /** Nothing is known about this album yet — the route's arguments have not been applied. */
    val isUnresolved: Boolean get() = userAlbum == null && systemAlbum == null && folderName == null
}

/**
 * The album detail screen: a window of items at a time, and the organisation actions over the selection.
 *
 * Reads are Room flows and writes go through the organisation repositories, so nothing here can reach
 * Telegram. The one platform call is the deletion consent request, which leaves as an
 * [IntentSenderRequest] because only an Activity may launch it — and nothing is removed from Trash until
 * [onDeletionResult] reports what the user answered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AlbumDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val target = MutableStateFlow<AlbumTarget?>(null)
    private val loadedLimit = MutableStateFlow(WINDOW_START)

    /** Grows as the add sheet's grid is scrolled; see [libraryItems]. */
    private val libraryLimit = MutableStateFlow(WINDOW_START)
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    /**
     * One-shot feedback for writes from this screen, as the strings to show.
     *
     * [MESSAGE_BUFFER] lines wait for a screen still showing the previous one; past that the oldest
     * is dropped rather than stalling the write that reported it. A line is only ever emitted here
     * for a failure the screen has no other way to show — the rename refusal reports under the
     * field instead, and the delete-forever answer is already a banner, so nothing is said twice.
     */
    private val _messages = MutableSharedFlow<AppMessage>(
        extraBufferCapacity = MESSAGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<AppMessage> = _messages.asSharedFlow()

    private val items: StateFlow<List<Media>?> = combine(target, loadedLimit) { value, limit -> value to limit }
        .flatMapLatest { (value, limit) -> contentsOf(value, limit) }
        .stateIn(viewModelScope, STOP_POLICY, null)

    private val userAlbum = target.flatMapLatest { value ->
        if (value is AlbumTarget.User) {
            container.albumRepository.observeAlbum(value.albumId)
        } else {
            flowOf(null)
        }
    }

    private val favoriteIds = items.flatMapLatest { list ->
        if (list == null) {
            flowOf(emptySet())
        } else {
            container.mediaOrganizationRepository.observeFavoritesWithin(list.map(Media::id))
        }
    }

    /** Which of the loaded window this album already holds, for the add-media sheet's ticks. */
    private val memberIds = combine(target, items) { value, list ->
        value to (list?.map { it.id } ?: emptyList())
    }.flatMapLatest { (value, ids) ->
        if (value is AlbumTarget.User && ids.isNotEmpty()) {
            flow { emit(container.albumRepository.membersWithin(value.albumId, ids).toSet()) }
        } else {
            flowOf(emptySet())
        }
    }

    val uiState: StateFlow<AlbumDetailUiState> = combine(
        userAlbum,
        items,
        favoriteIds,
        memberIds,
        selection,
    ) { album, media, favourites, members, chosen ->
        AlbumDetailUiState(
            userAlbum = album,
            systemAlbum = (target.value as? AlbumTarget.System)?.album,
            folderName = (target.value as? AlbumTarget.LocalFolder)
                ?.let { FolderPaths.displayNameOf(it.relativePath) },
            items = media,
            favoriteIds = favourites,
            memberIds = members,
            selection = chosen,
        )
    }.stateIn(viewModelScope, STOP_POLICY, AlbumDetailUiState())

    fun open(target: AlbumTarget) {
        if (this.target.value == target) return
        this.target.value = target
        selection.value = emptySet()
        loadedLimit.value = WINDOW_START
    }

    fun loadMore() {
        loadedLimit.value += WINDOW_STEP
    }

    fun onCellClick(mediaId: Long) {
        if (selection.value.isNotEmpty()) toggle(mediaId)
    }

    fun onCellLongClick(mediaId: Long) = toggle(mediaId)

    fun clearSelection() {
        selection.value = emptySet()
    }

    /**
     * Every id this album holds, with the same rule as the timeline's select all: the strip's count
     * is what the actions will act on, so the set may not stop at the loaded window. The three
     * targets mirror [contentsOf] one for one — an album of any kind answers with its whole self.
     */
    fun selectAll() {
        val current = target.value ?: return
        launchWrite("select all") {
            selection.value = when (current) {
                is AlbumTarget.User -> container.albumRepository.allMemberIds(current.albumId)
                is AlbumTarget.System -> container.mediaOrganizationRepository.allIdsIn(current.album)
                is AlbumTarget.LocalFolder ->
                    container.mediaOrganizationRepository.allIdsInLocalFolder(current.relativePath)
            }.toSet()
        }
    }

    /**
     * Resolves the selection to share targets and hands them to the screen for Android's share
     * sheet, through the same [com.lumovault.app.util.MediaShare] utility every other surface uses.
     *
     * The selection is deliberately kept — [withSelected] clears because its actions *change* the
     * album, and sharing changes nothing about it. Rows the index no longer holds are skipped by
     * the repository; when nothing at all can be shared the screen says so instead of opening an
     * empty sheet.
     */
    fun shareSelected(onShare: (List<ShareableMedia>) -> Unit) {
        val ids = selection.value
        if (ids.isEmpty()) return
        launchWrite("share selection") {
            val shareables = container.mediaRepository.shareablesFor(ids)
            if (shareables.isEmpty()) {
                _messages.tryEmit(AppMessage(R.string.share_nothing))
            } else {
                onShare(shareables)
            }
        }
    }

    fun setFavorite(favorite: Boolean) = withSelected(R.string.feedback_favorite_failed) { ids ->
        container.mediaOrganizationRepository.setFavorite(ids, favorite)
    }

    fun setArchived(archived: Boolean) = withSelected(R.string.feedback_archive_failed) { ids ->
        container.mediaOrganizationRepository.setArchived(ids, archived)
    }

    fun moveToTrash() = withSelected(R.string.feedback_trash_failed) { ids ->
        container.mediaOrganizationRepository.moveToTrash(ids)
    }

    fun restoreFromTrash() = withSelected { ids ->
        container.mediaOrganizationRepository.restoreFromTrash(ids)
    }

    fun removeFromAlbum() = withSelected { ids ->
        val albumId = (target.value as? AlbumTarget.User)?.albumId
        if (albumId != null) container.albumRepository.removeMedia(albumId, ids)
    }

    /**
     * The library the "add photos" sheet lists, and how much of it the index holds in total.
     *
     * A growing window, the same shape the album grid and the timeline use: the sheet used to offer a
     * fixed first page, which in a large library silently hid everything past it with no way to reach
     * it and no word of why the scrolling stopped. `libraryTotal` is what tells the sheet to stop
     * asking for more.
     */
    val libraryItems: StateFlow<List<Media>> = libraryLimit
        .flatMapLatest { limit -> container.mediaRepository.observeWindow(limit) }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    val libraryTotal: StateFlow<Int> = container.mediaRepository
        .observeCount()
        .stateIn(viewModelScope, STOP_POLICY, 0)

    fun loadLibraryMore() {
        libraryLimit.value += WINDOW_STEP
    }

    fun addMedia(ids: Collection<Long>) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId ?: return
        if (ids.isEmpty()) return
        launchWrite("add media") { container.albumRepository.addMedia(albumId, ids) }
    }

    /**
     * Renames the album, and reports the answer instead of assuming it.
     *
     * The repository answers a refusal with `false` — a name that was only whitespace — and a write
     * that throws ends the same way, so [onRefused] fires for either and the prompt stays open with
     * what was typed. Only [onRenamed], after the write has landed, lets the screen close it: this
     * dialog used to dismiss before calling here, so a refusal or a failure was invisible and the
     * old name silently remained. The failure is not also announced as a line at the bottom — the
     * dialog is on screen saying it under the field, and two copies of the same news are noise.
     */
    fun rename(name: String, onRenamed: () -> Unit, onRefused: () -> Unit) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId
        if (albumId == null) {
            // Only a user album can be renamed; saying refused is the honest answer for anything else
            // rather than leaving the prompt waiting for a write that was never asked for.
            onRefused()
            return
        }
        launchWrite("rename album", failureMessage = null, onFailure = onRefused) {
            if (container.albumRepository.rename(albumId, name)) onRenamed() else onRefused()
        }
    }

    /**
     * Deletes the album, then calls [onDeleted] so the screen can leave it.
     *
     * The callback rather than a flag in the state because this is the one action that makes the screen
     * itself invalid, and a boolean the composable polls would be a race with the flow that still names
     * the album it just removed. A deletion that throws never calls it, so the screen stays put on an
     * album that is still there.
     */
    fun deleteAlbum(onDeleted: () -> Unit) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId ?: return
        launchWrite("delete album") {
            container.albumRepository.delete(albumId)
            onDeleted()
        }
    }

    /**
     * Asks the device to delete the selection for good, handing the caller the consent to launch.
     *
     * [onUnsupported] is the API 29 answer, and it is shown rather than swallowed: that version lets an
     * app delete only media it holds a write grant for, and LumoVault does not ask for one. Either way
     * nothing leaves Trash until the device confirms it.
     */
    fun deleteForever(launch: (IntentSenderRequest) -> Unit, onUnsupported: () -> Unit) {
        val chosen = selection.value
        if (chosen.isEmpty()) return
        viewModelScope.launch {
            try {
                // Named from the whole Trash, not the scrolled window: the selection can now reach
                // past what is loaded (select all), and an item this request cannot name is an item
                // the user is about to lose without Android ever showing it in the consent dialog —
                // the rule [emptyTrash] already follows, applied to a subset of its own.
                val count = container.mediaOrganizationRepository.trashedCount()
                val trashed = if (count == 0) {
                    emptyList()
                } else {
                    container.mediaOrganizationRepository
                        .observeContents(SystemAlbum.Trash, count)
                        .first()
                }
                val named = trashed.filter { it.id in chosen }
                if (named.isEmpty()) {
                    // Nothing the device can show: every chosen row is organisation for a file the
                    // index no longer holds, which is bookkeeping rather than deletion — the same
                    // answer [emptyTrash] gives in its place.
                    container.mediaOrganizationRepository.forgetDeletedLocally(chosen)
                    selection.value = emptySet()
                    return@launch
                }
                requestDeletion(
                    ids = chosen,
                    uris = named.map { it.contentUri },
                    launch = launch,
                    onUnsupported = onUnsupported,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The same "cannot ask" state the refusal path shows: the honest answer when the
                // device could not be reached is a banner, not a crash mid-consent-flow.
                Log.w(TAG, "delete request failed: ${error.javaClass.simpleName}")
                onUnsupported()
            }
        }
    }

    /**
     * The same request over everything in Trash.
     *
     * Reads the whole Trash rather than the scrolled window: an item this request cannot name is an item
     * the user is about to lose without Android ever showing it in the consent dialog, and the dialog is
     * the one part of this path that is not LumoVault's promise to keep.
     */
    fun emptyTrash(launch: (IntentSenderRequest) -> Unit, onUnsupported: () -> Unit) {
        viewModelScope.launch {
            try {
                val count = container.mediaOrganizationRepository.trashedCount()
                if (count == 0) return@launch
                val trashed = container.mediaOrganizationRepository
                    .observeContents(SystemAlbum.Trash, count)
                    .first()
                if (trashed.isEmpty()) {
                    // Organisation rows for files the index no longer holds. Nothing to ask the device about,
                    // so this is bookkeeping rather than deletion.
                    container.mediaOrganizationRepository.forgetDeletedLocally(
                        container.mediaOrganizationRepository.trashedMediaIds(),
                    )
                    return@launch
                }
                requestDeletion(
                    ids = trashed.map { it.id }.toSet(),
                    uris = trashed.map { it.contentUri },
                    launch = launch,
                    onUnsupported = onUnsupported,
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The honest state is "the device could not be asked", not a crash mid-consent-flow;
                // the class name is the whole log, like every failure this app records.
                Log.w(TAG, "empty trash failed: ${error.javaClass.simpleName}")
                onUnsupported()
            }
        }
    }

    /**
     * Applies the device's answer.
     *
     * Only a confirmed result clears anything, and what it clears is local: the index row, the
     * organisation, the memberships. `backup_queue` and the cloud index are untouched, so the photo the
     * user deleted from the phone is still a cloud item — PRD section 72's intended outcome, and the
     * reason this method cannot reach Telegram even by accident.
     */
    fun onDeletionResult(resultCode: Int) {
        val ids = pendingDeletion
        pendingDeletion = null
        if (resultCode != Activity.RESULT_OK || ids == null || ids.isEmpty()) return
        viewModelScope.launch {
            try {
                container.mediaOrganizationRepository.forgetDeletedLocally(ids)
                selection.value = emptySet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The deletion itself already happened — the device confirmed it. A cleanup that failed
                // leaves rows the next scan sweeps; losing the app over them would misreport a success.
                Log.w(TAG, "post-deletion cleanup failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private var pendingDeletion: Set<Long>? = null

    private fun requestDeletion(
        ids: Set<Long>,
        uris: List<String>,
        launch: (IntentSenderRequest) -> Unit,
        onUnsupported: () -> Unit,
    ) {
        // The resolver can refuse outright — a uri whose grant died between listing and confirming is a
        // SecurityException, not a deletion. "Cannot ask" is the state the screen already knows how to show.
        val request = try {
            container.localMediaDeleter.requestFor(uris)
        } catch (error: Exception) {
            Log.w(TAG, "deletion request refused: ${error.javaClass.simpleName}")
            null
        }
        if (request == null) {
            pendingDeletion = null
            onUnsupported()
            return
        }
        pendingDeletion = ids
        launch(IntentSenderRequest.Builder(request.intentSender).setFillInIntent(null).build())
    }

    private suspend fun contentsOf(target: AlbumTarget?, limit: Int): Flow<List<Media>?> = when (target) {
        is AlbumTarget.User -> container.albumRepository.observeContents(target.albumId, limit)
        is AlbumTarget.System -> container.mediaOrganizationRepository.observeContents(target.album, limit)

        // Exact path, not a prefix: a folder's album is the files MediaStore files in *that* folder, and a
        // nested folder is its own album with its own count.
        is AlbumTarget.LocalFolder ->
            container.mediaOrganizationRepository.observeLocalFolderContents(target.relativePath, limit)

        // Null, not an empty list: nothing has been asked yet, and "no items" would be a false claim
        // until the first answer arrives.
        null -> flowOf(null)
    }

    private fun toggle(mediaId: Long) {
        selection.update { current -> if (mediaId in current) current - mediaId else current + mediaId }
    }

    /**
     * Runs one selected-item action and clears the selection only once it has landed.
     *
     * Clearing before the write would tell the action bar its rows were handled while Room was still
     * deciding; clearing in the success path only means a refusal leaves every id selected, which is
     * still true. Cancellation is rethrown, everything else is one class-name log line — a SQLite
     * message can quote a path — plus [failureMessage] for the screen, because a selection action
     * that failed and said nothing looks like one that did nothing at all.
     */
    private fun withSelected(
        failureMessage: Int = R.string.feedback_action_failed,
        action: suspend (Collection<Long>) -> Unit,
    ) {
        val ids = selection.value
        if (ids.isEmpty()) return
        launchWrite("selection action", failureMessage = failureMessage) {
            action(ids)
            selection.value = emptySet()
        }
    }

    /**
     * Companion to [withSelected] for the actions that name their own write in the log.
     *
     * [failureMessage] null means the caller shows the failure itself — [rename] puts it under the
     * field of a prompt that is still open — and [onFailure] runs in the catch alongside it, for
     * flags the screen sets before asking.
     */
    private fun launchWrite(
        description: String,
        failureMessage: Int? = R.string.feedback_action_failed,
        onFailure: () -> Unit = {},
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "$description failed: ${error.javaClass.simpleName}")
                onFailure()
                failureMessage?.let { _messages.tryEmit(AppMessage(it)) }
            }
        }
    }

    companion object {
        private const val TAG = "LumoVaultAlbumDetail"
        private const val WINDOW_START = 300
        private const val WINDOW_STEP = 300

        /** How many feedback lines wait for a screen that is still showing the previous one. */
        private const val MESSAGE_BUFFER = 8

        private val STOP_POLICY = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

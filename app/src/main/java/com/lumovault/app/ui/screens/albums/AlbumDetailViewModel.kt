package com.lumovault.app.ui.screens.albums

import android.app.Activity
import android.app.Application
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.Album
import com.lumovault.app.ui.navigation.AlbumTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
    val items: List<Media> = emptyList(),
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
    private val selection = MutableStateFlow<Set<Long>>(emptySet())

    private val items: StateFlow<List<Media>> = combine(target, loadedLimit) { value, limit -> value to limit }
        .flatMapLatest { (value, limit) -> contentsOf(value, limit) }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    private val userAlbum = target.flatMapLatest { value ->
        if (value is AlbumTarget.User) {
            container.albumRepository.observeAlbum(value.albumId)
        } else {
            flowOf(null)
        }
    }

    private val favoriteIds = items.flatMapLatest { list ->
        container.mediaOrganizationRepository.observeFavoritesWithin(list.map(Media::id))
    }

    /** Which of the loaded window this album already holds, for the add-media sheet's ticks. */
    private val memberIds = combine(target, items) { value, list ->
        value to list.map { it.id }
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

    fun setFavorite(favorite: Boolean) = withSelected { ids ->
        container.mediaOrganizationRepository.setFavorite(ids, favorite)
    }

    fun setArchived(archived: Boolean) = withSelected { ids ->
        container.mediaOrganizationRepository.setArchived(ids, archived)
    }

    fun moveToTrash() = withSelected { ids -> container.mediaOrganizationRepository.moveToTrash(ids) }

    fun restoreFromTrash() = withSelected { ids ->
        container.mediaOrganizationRepository.restoreFromTrash(ids)
    }

    fun removeFromAlbum() = withSelected { ids ->
        val albumId = (target.value as? AlbumTarget.User)?.albumId
        if (albumId != null) container.albumRepository.removeMedia(albumId, ids)
    }

    /**
     * The library the "add photos" sheet lists.
     *
     * Windowed by the media index's own limit rather than by the album's, because the sheet is choosing
     * from the device and not from what is already here; the album's members come from
     * [AlbumDetailUiState.memberIds] so the already-added ones show ticked.
     */
    val libraryItems: StateFlow<List<Media>> = container.mediaRepository
        .observeWindow(SHEET_WINDOW)
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    fun addMedia(ids: Collection<Long>) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId ?: return
        if (ids.isEmpty()) return
        viewModelScope.launch { container.albumRepository.addMedia(albumId, ids) }
    }

    fun rename(name: String) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId ?: return
        viewModelScope.launch { container.albumRepository.rename(albumId, name) }
    }

    /**
     * Deletes the album, then calls [onDeleted] so the screen can leave it.
     *
     * The callback rather than a flag in the state because this is the one action that makes the screen
     * itself invalid, and a boolean the composable polls would be a race with the flow that still names
     * the album it just removed.
     */
    fun deleteAlbum(onDeleted: () -> Unit) {
        val albumId = (target.value as? AlbumTarget.User)?.albumId ?: return
        viewModelScope.launch {
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
        requestDeletion(
            ids = chosen,
            uris = urisForIds(items.value, chosen),
            launch = launch,
            onUnsupported = onUnsupported,
        )
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

    private fun urisForIds(items: List<Media>, ids: Set<Long>): List<String> =
        items.filter { it.id in ids }.map { it.contentUri }

    private suspend fun contentsOf(target: AlbumTarget?, limit: Int) = when (target) {
        is AlbumTarget.User -> container.albumRepository.observeContents(target.albumId, limit)
        is AlbumTarget.System -> container.mediaOrganizationRepository.observeContents(target.album, limit)

        // Exact path, not a prefix: a folder's album is the files MediaStore files in *that* folder, and a
        // nested folder is its own album with its own count.
        is AlbumTarget.LocalFolder ->
            container.mediaOrganizationRepository.observeLocalFolderContents(target.relativePath, limit)

        null -> flowOf<List<Media>>(emptyList())
    }

    private fun toggle(mediaId: Long) {
        selection.update { current -> if (mediaId in current) current - mediaId else current + mediaId }
    }

    private fun withSelected(action: suspend (Collection<Long>) -> Unit) {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            action(ids)
            selection.value = emptySet()
        }
    }

    companion object {
        private const val TAG = "LumoVaultAlbumDetail"
        private const val WINDOW_START = 300
        private const val WINDOW_STEP = 300

        /** How many library items the add sheet offers at once; the same bound as the timeline's first page. */
        private const val SHEET_WINDOW = 300
        private val STOP_POLICY = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        private const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

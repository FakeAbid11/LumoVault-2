package com.lumovault.app.ui.viewer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.metadata.MetadataCandidate
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.organization.Album
import com.lumovault.app.ui.navigation.ViewerTarget
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * One media item, in the context of the list it came from.
 *
 * Three things this class owns that the screen must not: which window of the source list is loaded, what the
 * shown item's backup and organisation states are, and when a file's EXIF is read. The last is lazy on purpose
 * — the viewer is the one place a person actually looks at a photograph, so it is the one place worth opening
 * the file for — and it is guarded per item per process, because a photo with no EXIF would otherwise be
 * reopened on every visit to find, again, that it has none.
 *
 * Nothing here reaches Telegram. The backup action writes to the same queue the Photos screen uses, which is
 * the point of it: a photo backed up from here goes through recognition, staging and the worker like any other
 * upload, and this class has no path that could skip them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaViewerViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val request = MutableStateFlow<OpenRequest?>(null)
    private val loadedLimit = MutableStateFlow(WINDOW_START)
    private val page = MutableStateFlow(0)

    /** Once per item per process, so "this file has no EXIF" is not discovered again on every visit. */
    private val metadataReadAttempted = mutableSetOf<Long>()

    /**
     * The source's window, or null while Room has not answered about it yet.
     *
     * Null and empty are different facts and the screen has to be able to tell them apart: an empty list is
     * "this album no longer holds the photo you tapped", which is worth saying out loud, while null is "the
     * query has not come back", which is not.
     */
    private val rows: StateFlow<List<Media>?> = combine(request, loadedLimit) { value, limit -> value to limit }
        .flatMapLatest { (value, limit) -> contentsOf(value?.target, limit) }
        .stateIn(viewModelScope, STOP_POLICY, null)

    /**
     * The list and the page it was opened on, in one value, because in two they are a race.
     *
     * Read separately they can disagree for a frame — the window lands before the index is recomputed — and a
     * pager built on that frame opens on page zero and quietly shows a different photograph than the one that
     * was tapped. Which is the one failure a viewer must not have.
     */
    val listing: StateFlow<Listing> = combine(rows, request) { list, value ->
        when (list) {
            null -> Listing.Loading
            else -> {
                val index = ViewerPresentation.pageIndex(list.map(Media::id), value?.mediaStoreId ?: NO_ID)
                if (index < 0) Listing.Missing(list.size) else Listing.Ready(list, index)
            }
        }
    }.stateIn(viewModelScope, STOP_POLICY, Listing.Loading)

    /** The item the pager has settled on. */
    val currentItem: StateFlow<Media?> = combine(listing, page) { state, index ->
        (state as? Listing.Ready)?.items?.getOrNull(index)
    }.stateIn(viewModelScope, STOP_POLICY, null)

    /**
     * The shown item's queue state, or null when it has no record.
     *
     * Asked for one id rather than the window: the viewer shows one photo at a time, and a 300-item `IN` list
     * per swipe is a query for information nobody is looking at.
     */
    val currentBackupState: StateFlow<UploadState?> = currentItem
        .flatMapLatest { media ->
            val id = media?.id
            if (id == null) {
                flowOf(null)
            } else {
                container.backupQueueRepository.observeStatesFor(listOf(id)).map { states -> states[id] }
            }
        }
        .stateIn(viewModelScope, STOP_POLICY, null)

    val currentFavorite: StateFlow<Boolean> = currentItem
        .flatMapLatest { media ->
            val id = media?.id
            if (id == null) {
                flowOf(false)
            } else {
                container.mediaOrganizationRepository.observeFavoritesWithin(listOf(id)).map { it.contains(id) }
            }
        }
        .stateIn(viewModelScope, STOP_POLICY, false)

    /**
     * EXIF for the shown item, read the first time it is asked for.
     *
     * Photos only. A GIF carries no EXIF and a video's location is not in an EXIF block, so opening either
     * would cost a read that can only answer nothing — and would then store that nothing as though it were a
     * fact about the file.
     */
    val currentMetadata: StateFlow<MediaMetadata?> = currentItem
        .flatMapLatest { media ->
            if (media == null || media.type != MediaType.Photo) {
                flowOf(null)
            } else {
                val id = media.id
                val uri = media.contentUri
                container.mediaMetadataRepository.observe(id).map { found ->
                    if (found == null && metadataReadAttempted.add(id)) {
                        viewModelScope.launch {
                            container.extractMediaMetadata.readOne(MetadataCandidate(id, uri))
                        }
                    }
                    found
                }
            }
        }
        .stateIn(viewModelScope, STOP_POLICY, null)

    /** The albums the shown item can be filed into. */
    val userAlbums: StateFlow<List<Album>> = container.albumRepository.observeAlbums()
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    fun open(mediaStoreId: Long, target: ViewerTarget) {
        if (request.value?.matches(mediaStoreId, target) == true) return
        request.value = OpenRequest(mediaStoreId, target)
        loadedLimit.value = WINDOW_START
        page.value = 0
    }

    fun pageSettled(index: Int) {
        page.value = index
    }

    /** Widens the window from behind, the way the grids do it, so swiping to the end is not a wall. */
    fun loadMore() {
        loadedLimit.value += WINDOW_STEP
    }

    /**
     * Queues the shown item.
     *
     * The same two steps the Photos bar makes — write the records, then ask for a pass — because a manually
     * backed-up photo must pass through recognition and the worker like every other upload. There is no second
     * upload path here and no way to reach one from this class.
     */
    fun backUpCurrent() {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch {
            container.backupQueueRepository.enqueue(listOf(id))
            container.backupScheduler.start()
        }
    }

    /** A failed item re-queued through the queue's own rules rather than restarted from here. */
    fun retryCurrent() {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch {
            container.backupQueueRepository.enqueue(listOf(id))
            container.backupScheduler.start()
        }
    }

    /** Favourites the shown item. Nothing on this path can enqueue an upload: the two tables never meet. */
    fun setFavorite(favorite: Boolean) {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch { container.mediaOrganizationRepository.setFavorite(listOf(id), favorite) }
    }

    /**
     * Archives the shown item, and does not touch the list.
     *
     * The source's own query hides archived items, so Room drops the row and the pager shrinks by one. Editing
     * it here as well is how a viewer ends up showing a photograph that has already left.
     */
    fun archiveCurrent() {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch { container.mediaOrganizationRepository.setArchived(listOf(id), true) }
    }

    fun moveToTrashCurrent() {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch { container.mediaOrganizationRepository.moveToTrash(listOf(id)) }
    }

    fun addToAlbum(albumId: Long) {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch { container.albumRepository.addMedia(albumId, listOf(id)) }
    }

    /**
     * Hands the map this item's position, so it opens centred on the photograph rather than on the library.
     *
     * A no-op when the file has no coordinates — and the button that calls this is only drawn when it does.
     */
    fun requestMapFocus() {
        val id = currentItem.value?.id ?: return
        viewModelScope.launch {
            container.mediaMetadataRepository.locationFor(id)?.let { container.mapFocus.request(it) }
        }
    }

    private suspend fun contentsOf(target: ViewerTarget?, limit: Int) = when (target) {
        null, is ViewerTarget.Photos -> container.mediaRepository.observeWindow(limit)
        is ViewerTarget.Album -> container.albumRepository.observeContents(target.albumId, limit)
        is ViewerTarget.SystemAlbumView -> container.mediaOrganizationRepository.observeContents(target.album, limit)

        // The same list the folder's album screen drew, so swiping left and right inside a folder stays
        // inside that folder.
        is ViewerTarget.Folder ->
            container.mediaOrganizationRepository.observeLocalFolderContents(target.relativePath, limit)
    }

    private data class OpenRequest(val mediaStoreId: Long, val target: ViewerTarget) {
        fun matches(id: Long, other: ViewerTarget) = mediaStoreId == id && target == other
    }

    private companion object {
        const val WINDOW_START = 300
        const val WINDOW_STEP = 300

        /** No request yet, so nothing to look for — which resolves to [Listing.Loading] either way. */
        const val NO_ID = -1L

        val STOP_POLICY = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * What the viewer is allowed to draw.
 *
 * [Listing.Missing] carries the size of the list it searched, because the two cases read differently to a
 * person: an album that has gone empty, and a library of nine hundred photos that no longer holds this one,
 * are both "not here" — and only one of them suggests the photo was deleted.
 */
sealed interface Listing {
    /** Room has not answered about the source list yet. */
    data object Loading : Listing

    /** The list is there; the tapped item is not in it any more. */
    data class Missing(val listSize: Int) : Listing

    /** The list, and the page the route asked for. */
    data class Ready(val items: List<Media>, val initialPage: Int) : Listing
}

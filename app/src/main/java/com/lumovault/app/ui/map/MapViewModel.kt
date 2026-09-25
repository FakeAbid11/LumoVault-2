package com.lumovault.app.ui.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.data.map.MapTileProvider
import com.lumovault.app.domain.map.MapClustering
import com.lumovault.app.domain.map.MapPin
import com.lumovault.app.domain.map.MapViewport
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MapPhoto
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
 * The map's state: what is on screen, what is placed, and what the user has picked.
 *
 * Everything is derived from one input — the viewport the map widget last reported. The photo query, the
 * clustering and the strip under the map are all that viewport re-read through Room, which is what keeps a pan
 * from becoming four separate ideas of "where am I looking" that can disagree.
 *
 * Nothing here opens a media file. The positions come from the metadata table the bounded EXIF pass fills, so
 * the map reads a database while a library of 100,000 photos is still being worked through, and shows what it
 * has rather than waiting for what it does not.
 */
class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val viewport = MutableStateFlow<MapViewport?>(null)
    private val selection = MutableStateFlow<MapPin?>(null)

    /** Where the viewer asked to be placed, consumed once so a later visit sees the whole library again. */
    private val focus = MutableStateFlow<MediaLocation?>(null)

    /** Live, because the grant can be revoked from system settings and a remembered answer would lie. */
    private val locationsAllowed = MutableStateFlow(false)

    /** Whether this build was given a tile host. A fact about the build, so read once. */
    val tilesConfigured: Boolean = MapTileProvider.isConfigured

    val attribution: String = MapTileProvider.attribution

    /** The positioned photos inside the current viewport, newest capture first. */
    val photos: StateFlow<List<MapPhoto>> = viewport
        .flatMapLatest { window ->
            if (window == null) {
                flowOf(emptyList())
            } else {
                container.mediaMetadataRepository.observeMapPhotos(window.bounds, PHOTO_LIMIT)
            }
        }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    /**
     * What to draw, which is not the same list as what is stored.
     *
     * Recomputed from [photos] and the viewport's zoom, so zooming in separates a cluster into its members
     * without any state being carried between the two levels.
     */
    val pins: StateFlow<List<MapPin>> = combine(photos, viewport) { found, window ->
        MapClustering.cluster(found, zoom = window?.zoom ?: MapClustering.DEFAULT_ZOOM)
    }.stateIn(viewModelScope, STOP_POLICY, emptyList())

    /** The strip: the same window's photos, capped at what a row can usefully show. */
    val strip: StateFlow<List<MapPhoto>> = photos
        .map { found -> found.take(STRIP_LIMIT) }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    val selected: StateFlow<MapPin?> = selection

    val focusLocation: StateFlow<MediaLocation?> = focus

    /**
     * How many positioned photos exist at all.
     *
     * This is the number that separates the map's two silences: a library with no GPS in it, and a library
     * whose EXIF has not been read yet. Only the second one has something to ask the user for.
     */
    val locatedCount: StateFlow<Int> = container.mediaMetadataRepository.observeLocatedCount()
        .stateIn(viewModelScope, STOP_POLICY, 0)

    /** How many photos are still waiting to be opened for their metadata. */
    private val pendingExtraction = MutableStateFlow(0)

    val extractionPending: StateFlow<Int> = pendingExtraction

    val state: StateFlow<MapState> = combine(
        pins,
        locatedCount,
        pendingExtraction,
        locationsAllowed,
    ) { drawn, placed, waiting, allowed ->
        MapState(
            hasPins = drawn.isNotEmpty(),
            placedCount = placed,
            locationsAllowed = allowed,
            // The one silence worth breaking: nothing is plotted, files are still unread, and the reason
            // they are unread is a permission the user has not been asked for.
            shouldAskForLocations = !allowed && placed == 0 && waiting > 0,
            extractionWaiting = waiting,
        )
    }.stateIn(viewModelScope, STOP_POLICY, MapState())

    init {
        // The pass the map exists to feed: opening it is the only moment reading a photo's GPS is worth the
        // I/O, so there is no background scheduler for this and no poll.
        container.startMetadataExtraction()
        viewModelScope.launch { pendingExtraction.value = container.mediaMetadataRepository.pendingExtractionCount() }
    }

    fun permissionToRequest(): String = container.permissionRepository.mediaLocationPermissionToRequest()

    /** Re-read on every resume, because the grant can change while LumoVault is in the background. */
    fun resume() {
        viewModelScope.launch {
            val allowed = container.permissionRepository.mediaLocationGranted()
            locationsAllowed.value = allowed
            pendingExtraction.value = container.mediaMetadataRepository.pendingExtractionCount()
            if (allowed) container.startMetadataExtraction()
        }
        focus.value = container.mapFocus.consume()
    }

    /**
     * The answer to the permission dialog, and the one thing that has to be undone after it.
     *
     * Android redacts GPS from an app that does not hold the permission, so any read done before this point
     * correctly reported "no coordinates" — and keeping those rows would leave the map empty forever however
     * honestly each read had been logged. Dropping them makes exactly those files candidates again.
     */
    fun onPermissionResult() {
        viewModelScope.launch {
            val allowed = container.permissionRepository.mediaLocationGranted()
            locationsAllowed.value = allowed
            if (allowed) {
                container.mediaMetadataRepository.discardUnlocatedReads()
            }
            pendingExtraction.value = container.mediaMetadataRepository.pendingExtractionCount()
            container.startMetadataExtraction()
        }
    }

    fun viewportChanged(bounds: MapBounds, zoomLevel: Double) {
        val next = MapViewport(bounds, MapClustering.zoomOf(zoomLevel))
        val previous = viewport.value
        if (previous == next) return
        viewport.value = next
        // Re-clustering replaces every pin object, so a card left open on the old one now points at a marker
        // that no longer exists. Dropped on a zoom change; a pan keeps it, because the pin is still drawn.
        if (previous?.zoom != next.zoom) selection.value = null
    }

    fun select(pin: MapPin?) {
        selection.value = pin
    }

    fun clearSelection() {
        selection.value = null
    }

    fun focusConsumed() {
        focus.value = null
    }

    /** The photos behind a cluster, bounded — the preview card shows a handful and the viewer pages the rest. */
    suspend fun photosIn(pin: MapPin, limit: Int): List<MapPhoto> =
        container.mediaMetadataRepository.mapPhotos(pin.mediaStoreIds, limit)

    private companion object {
        /**
         * Photos a single viewport may pull.
         *
         * Clustering is what makes a big number cheap to draw, not this limit; the limit exists because a
         * pinch-out over a country can otherwise ask Room to hand over every photo the device holds and put
         * them all in a list, and that is a memory failure rather than a slow map.
         */
        const val PHOTO_LIMIT = 2_000

        const val STRIP_LIMIT = 20

        val STOP_POLICY = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS)
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** Which of the map's silences is on screen. Drawn by the screen, decided here. */
data class MapState(
    val hasPins: Boolean = false,
    val placedCount: Int = 0,
    /** Whether Android is currently handing this app unredacted EXIF at all. */
    val locationsAllowed: Boolean = false,
    /** The one silence worth breaking with a request: see the derivation above for the conjunction. */
    val shouldAskForLocations: Boolean = false,
    val extractionWaiting: Int = 0,
)

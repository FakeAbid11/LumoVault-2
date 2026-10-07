package com.lumovault.app.ui.map

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.data.map.MapTileProvider
import com.lumovault.app.domain.map.MapClustering
import com.lumovault.app.domain.map.MapFraming
import com.lumovault.app.domain.map.MapPin
import com.lumovault.app.domain.map.MapPlacement
import com.lumovault.app.domain.map.MapViewport
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MapPhoto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.sample

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

    /**
     * The positioned photos inside the current viewport, newest capture first.
     *
     * A failed read answers with nothing and says so, for the same reason the rest of this file keeps its
     * silences apart: "there is nothing here" and "I could not find out" look identical as an empty list,
     * and only one of them is worth a sentence on screen. The flag is cleared by the next answer that
     * arrives, so a pan past a transient failure takes the notice down with it.
     */
    private val readFailed = MutableStateFlow(false)

    val photos: StateFlow<List<MapPhoto>> = viewport
        .flatMapLatest { window ->
            if (window == null) {
                flowOf(emptyList())
            } else {
                container.mediaMetadataRepository.observeMapPhotos(window.bounds, PHOTO_LIMIT)
                    .onEach { readFailed.value = false }
                    .catch { error ->
                        // Class name only: a SQLite message can quote a path.
                        Log.w(TAG, "map query failed: ${error.javaClass.simpleName}")
                        readFailed.value = true
                        emit(emptyList())
                    }
            }
        }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    /**
     * What to draw, which is not the same list as what is stored.
     *
     * Recomputed from [photos] and the viewport's zoom, so zooming in separates a cluster into its members
     * without any state being carried between the two levels.
     */
    val pins: StateFlow<List<MapPin>> = combine(
        // Coalesced before it is clustered: one metadata pass writes hundreds of rows, each of which
        // invalidates the query underneath this, and a map that redrew its markers once per row would be
        // rebuilding thousands of objects to show a screen nobody has finished looking at.
        photos.sample(PINS_SAMPLE_MILLIS),
        viewport,
    ) { found, window ->
        MapClustering.cluster(found, zoom = window?.zoom ?: MapClustering.DEFAULT_ZOOM)
    }
        // Up to [PHOTO_LIMIT] rows grouped into cells, and `stateIn` would run it on the thread that
        // collects — the main one, while the user is panning.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    /** The strip: the same window's photos, capped at what a row can usefully show. */
    val strip: StateFlow<List<MapPhoto>> = photos
        .map { found -> found.take(STRIP_LIMIT) }
        .stateIn(viewModelScope, STOP_POLICY, emptyList())

    val selected: StateFlow<MapPin?> = selection

    val focusLocation: StateFlow<MediaLocation?> = focus

    private val _placement = MutableStateFlow<MapPlacement?>(null)

    /**
     * How the map should frame the library the first time it can.
     *
     * Set once per view model, and cleared when the screen has acted on it — see
     * [MapFraming.shouldPlace]. A map that recentres on its photos every time the query answer changes is a
     * map the user cannot move, so this is a one-shot request rather than a standing fact about the library.
     */
    val placement: StateFlow<MapPlacement?> = _placement

    /** Whether this view model has already had its map moved to the photos. */
    private var framed = false

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
        readFailed,
    ) { drawn, placed, waiting, allowed, failed ->
        mapStateFor(
            drawnPins = drawn.size,
            located = placed,
            waiting = waiting,
            allowed = allowed,
            readFailed = failed,
        )
    }.stateIn(viewModelScope, STOP_POLICY, MapState())

    init {
        // The pass the map exists to feed: opening it is the only moment reading a photo's GPS is worth the
        // I/O, so there is no background scheduler for this and no poll.
        container.startMetadataExtraction()
        viewModelScope.launch { pendingExtraction.value = container.mediaMetadataRepository.pendingExtractionCount() }
        viewModelScope.launch {
            // Framing follows the count rather than happening once at open, because the common first visit is
            // a library whose EXIF has not been read yet: framing at that moment would leave the map on the
            // Gulf of Guinea with a hundred photos somewhere off screen. The count going from nothing to
            // something is the moment the map can be pointed at the library — and [frameOnLibrary] is a
            // one-shot after that, so no later change moves a map the user has already started looking at.
            container.mediaMetadataRepository.observeLocatedCount()
                .distinctUntilChanged()
                .collect { frameOnLibrary() }
        }
    }

    /**
     * Ask the table where the positioned photos are, and hold the answer until the screen takes it.
     *
     * Two guards keep this from becoming a query per database invalidation: [framed] makes it a one-shot per
     * view model, and a [_placement] nobody has acted on yet is itself a reason not to ask again. The case the
     * second guard covers is the metadata pass finishing while the map sits open — hundreds of rows landing,
     * each invalidating the flow underneath it.
     *
     * No dispatcher is forced around the query: a Room `suspend` function already runs off the caller's
     * thread, and the answer is five numbers rather than a window's worth of photos.
     */
    private suspend fun frameOnLibrary() {
        if (framed || _placement.value != null) return
        val framing = MapFraming.placementFor(container.mediaMetadataRepository.locatedBounds())
        if (MapFraming.shouldPlace(framed, framing)) _placement.value = framing
    }

    /** The screen has moved the map. Never ask it to move again for this visit. */
    fun placementConsumed() {
        framed = true
        _placement.value = null
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
    suspend fun photosIn(pin: MapPin, limit: Int): List<MapPhoto> = try {
        container.mediaMetadataRepository.mapPhotos(pin.mediaStoreIds, limit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // The preview card is the caller, and it already knows how to show a pin with nothing behind it:
        // a count and an Open button over the cluster that was tapped. An exception left to reach the
        // composition instead would take the whole screen down over a preview strip.
        Log.w(TAG, "pin photos failed: ${error.javaClass.simpleName}")
        emptyList()
    }

    private companion object {
        private const val TAG = "LumoVaultMap"

        /** How long the pin layer waits for a burst of database invalidations to settle. */
        const val PINS_SAMPLE_MILLIS = 250L

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

/**
 * Which of the map's silences is on screen. Drawn by the screen, decided here.
 *
 * The screen offers exactly four sentences, in this order: [shouldAskForLocations] (a permission nobody has
 * asked for yet), [locationsUnavailable] (the read failed), "files are still being read", and "nothing here
 * records where it was taken". They look identical from outside — an empty pin list — and only one of them
 * is actionable, so telling them apart is the whole job of this data class. [locationsUnavailable] in
 * particular must never be shown as an empty map: the photographs are probably still there.
 */
data class MapState(
    val hasPins: Boolean = false,
    val placedCount: Int = 0,
    /** Whether Android is currently handing this app unredacted EXIF at all. */
    val locationsAllowed: Boolean = false,
    /** The one silence worth breaking with a request: see the derivation below. */
    val shouldAskForLocations: Boolean = false,
    val extractionWaiting: Int = 0,
    /** The query failed rather than answered nothing. */
    val locationsUnavailable: Boolean = false,
)

/**
 * The whole of [MapState] as a decision over its five inputs, so it can be asked of without a view model,
 * a Room instance or an Application.
 *
 * The conjunction that decides which silence is on top is the reason this is a function rather than five
 * fields the screen would have to agree about on its own — and it is what the tests assert, because the
 * alternative to a wrong conjunction is a notice that sends the user to fix the wrong thing.
 */
internal fun mapStateFor(
    drawnPins: Int,
    located: Int,
    waiting: Int,
    allowed: Boolean,
    readFailed: Boolean,
): MapState = MapState(
    hasPins = drawnPins > 0,
    placedCount = located,
    locationsAllowed = allowed,
    // The one silence worth breaking: nothing is plotted, files are still unread, and the reason they are
    // unread is a permission the user has not been asked for.
    shouldAskForLocations = !allowed && located == 0 && waiting > 0,
    extractionWaiting = waiting,
    locationsUnavailable = readFailed,
)

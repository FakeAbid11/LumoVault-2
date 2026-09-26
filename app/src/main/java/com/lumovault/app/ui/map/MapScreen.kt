package com.lumovault.app.ui.map

import android.view.ViewTreeObserver
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import com.lumovault.app.R
import android.net.Uri
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import com.lumovault.app.data.map.MapTileProvider
import com.lumovault.app.domain.map.MapClustering
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.map.MapPlacement
import com.lumovault.app.domain.map.MapPin
import com.lumovault.app.domain.model.MapPhoto
import java.text.DateFormat
import java.util.Date
import kotlin.coroutines.resume
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import com.lumovault.app.ui.theme.MapNoticeScrim
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs
import androidx.compose.ui.graphics.toArgb

/**
 * The photo map: every placed photograph in the rectangle the user is looking at.
 *
 * It is a map of the library, not a map of the world — the difference shows up in what this screen refuses to
 * do. It never asks where the phone is, because PRD section 30 wants the locations the photographs carry. It
 * never downloads a stored original to draw a marker, because the thumbnail on the device is already there.
 * And it does not pretend to have tiles when the build has no tile host: the markers, the clusters, the strip
 * and the hand-off to the viewer all work without a single tile, so an unconfigured build shows a real map of
 * a plain canvas and says so, rather than a grey rectangle that looks like a network failure.
 *
 * A pan re-queries Room and re-clusters; a zoom does the same and, because the cluster cell is a fixed number
 * of pixels while the world grows fourfold per level, the same code separates a city into its streets. That is
 * the whole reason clustering lives in pure Kotlin — see [MapClustering] — rather than in a library that would
 * not show its work here.
 *
 * Two things about the first frame of a map are only visible when they are wrong, so both are done here and
 * neither is left to chance. The viewport is published as soon as the map has been **laid out**, because
 * osmdroid's bounding box is a field rather than a nullable and before a layout pass it is a rectangle with no
 * area — a query given that rectangle finds no photos, and the screen stays empty for the rest of the visit
 * however many positions the library holds. And the map is moved **once**, to where [MapFraming] says the
 * photos are, because a map that recentres whenever the viewport changes is a map the user cannot move.
 */
@Composable
fun MapScreen(
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MapViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pins by viewModel.pins.collectAsStateWithLifecycle()
    val strip by viewModel.strip.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val focus by viewModel.focusLocation.collectAsStateWithLifecycle()
    val placement by viewModel.placement.collectAsStateWithLifecycle()

    var mapView by remember { mutableStateOf<MapView?>(null) }
    var previews by remember { mutableStateOf<List<MapPhoto>>(emptyList()) }

    /**
     * Bumped on every resume, because osmdroid clears what it was given.
     *
     * `MapView.onDetach` empties the listener list and the overlay manager's list, and a Compose subtree
     * leaving and coming back is this app's normal navigation. Without a key that changes, the second visit
     * to the map is a picture of the library with no listener attached to it, no tap receiver, and no markers
     * — the effects below would not re-run, because the view object is the same one.
     */
    var visit by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { viewModel.onPermissionResult() }

    // The map is only worth reading positions for while it is being looked at, so the pass is started by the
    // screen appearing rather than by a scheduled job that would run against nobody's interest.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.resume()
                visit += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { viewContext ->
            MapTileProvider.configure(viewContext)
            MapView(viewContext).apply {
                // A MapView destroys itself on detach by default, and a Compose subtree leaving and returning
                // is normal navigation — without this the second visit would be a map with no overlays and no
                // listeners at all. The teardown is not lost, only moved: the `onRelease` handler below is the
                // point where leaving is permanent, and every visit otherwise only pauses the view.
                setDestroyMode(false)
                val source = MapTileProvider.tileSource()
                if (source == null) {
                    // osmdroid's own default tile source is Mapnik — https://tile.openstreetmap.org/ — so a
                    // build that was handed no provider would otherwise fetch from the public servers whose
                    // usage policy the header comment of MapTileProvider describes. With the data connection
                    // off, nothing is requested for a tile: the map plots every photo on a blank canvas and the
                    // notice below says that this is a build without a provider, not a feature that is missing.
                    setUseDataConnection(false)
                } else {
                    setTileSource(source)
                }
                setMultiTouchControls(true)
                setZoomLevel(MapClustering.DEFAULT_ZOOM.toDouble())
            }
        },
        update = { view -> mapView = view },
        // The renderer is stopped when the screen goes for good. osmdroid 6.1.20 exposes no destroy on
        // `MapView` — `onPause`, `onResume`, `onDetach` and `setDestroyMode` are the whole surface, checked
        // against the artifact's own sources — so the pause is what this build can ask for, and the view
        // itself stays reachable only through the composition that just went away.
    )

    DisposableEffect(mapView, visit) {
        val map = mapView ?: return@DisposableEffect onDispose { }
        map.onResume()
        // osmdroid reports one scroll event per frame and its events carry no usable coordinates, so the
        // listener is a debounced trigger and the bounding box is re-read from the map itself.
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                publishViewport(map, viewModel)
                return true
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                publishViewport(map, viewModel)
                return true
            }
        }
        val delayed = org.osmdroid.events.DelayedMapListener(listener, VIEWPORT_DEBOUNCE_MILLIS)
        map.addMapListener(delayed)
        // Taps on empty map close the preview card. Added first so markers, which osmdroid asks in reverse
        // order, keep priority over it.
        val events = MapEventsOverlay(
            object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(position: GeoPoint?): Boolean {
                    viewModel.clearSelection()
                    return false
                }

                override fun longPressHelper(position: GeoPoint?): Boolean = false
            },
        )
        map.overlays.add(0, events)
        onDispose {
            map.removeMapListener(delayed)
            map.overlays.remove(events)
            map.onPause()
        }
    }

    // Resolved out here because the overlay code below is not a composable and cannot ask the theme for a
    // colour. A data class, so that a recomposition that changes nothing draws nothing: as an ordinary class
    // it would be a new instance every frame, and the effect keyed on it would rebuild every marker each time.
    val markerStyle = MarkerStyle(
        chipBackground = MaterialTheme.colorScheme.primary.toArgb(),
        chipText = MaterialTheme.colorScheme.onPrimary.toArgb(),
        textLabelPx = with(density) { MarkerLabelSize.toPx().toInt() },
    )

    LaunchedEffect(mapView, visit, pins, markerStyle) {
        val map = mapView ?: return@LaunchedEffect
        drawPins(map, pins, viewModel, markerStyle)
    }

    /**
     * Put the map where the library is, once, and then tell the model what it is looking at.
     *
     * Both halves have to wait for a layout: osmdroid's bounding box is a field rather than a nullable, so
     * before the first layout it is a rectangle with no area — published as a viewport, the query answers
     * nothing and the screen looks empty for the rest of the visit; and fitting a box to a view whose size is
     * not known yet picks a zoom for a screen of zero pixels. [awaitMapMeasured] is what makes "after the
     * layout" a fact instead of a hope.
     *
     * A focus request from the viewer wins over the framing. It is the answer to a tap on one photograph, and
     * dropping a pending frame at the same time is what stops the map jumping away from the photo the user
     * just asked for, one recomposition later.
     */
    LaunchedEffect(mapView, visit, placement, focus) {
        val map = mapView ?: return@LaunchedEffect
        if (placement == null && focus == null) return@LaunchedEffect
        awaitMapMeasured(map)
        val location = focus
        // Copied out of the delegated properties before the `when`: a `by`-delegated value cannot be
        // smart-cast, and the two branches below need the concrete placement's own fields.
        val framing = placement
        when {
            location != null -> {
                map.controller.setCenter(GeoPoint(location.latitude, location.longitude))
                if (map.zoomLevelDouble < FOCUS_ZOOM) map.setZoomLevel(FOCUS_ZOOM)
                viewModel.focusConsumed()
                viewModel.placementConsumed()
            }

            framing is MapPlacement.Fit -> {
                val bounds = framing.bounds
                // North, east, south, west — the order `BoundingBox` takes, which is not the order
                // `MapBounds` holds (min/max), and swapping either one silently frames the wrong quarter of
                // the planet.
                map.zoomToBoundingBox(
                    BoundingBox(
                        bounds.maxLatitude, bounds.maxLongitude,
                        bounds.minLatitude, bounds.minLongitude,
                    ),
                    false,
                    with(density) { FIT_BORDER.toPx().toInt() },
                )
                viewModel.placementConsumed()
            }

            framing is MapPlacement.Centre -> {
                map.controller.setCenter(GeoPoint(framing.at.latitude, framing.at.longitude))
                map.setZoomLevel(framing.zoom)
                viewModel.placementConsumed()
            }

            else -> Unit
        }
        publishViewport(map, viewModel)
    }

    LaunchedEffect(selected) {
        val pin = selected ?: run { previews = emptyList(); return@LaunchedEffect }
        previews = viewModel.photosIn(pin, PREVIEW_LIMIT)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (!viewModel.tilesConfigured) {
            MapNotice(
                text = stringResource(R.string.map_tiles_unconfigured),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        if (state.shouldAskForLocations) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (viewModel.tilesConfigured) 8.dp else 64.dp)
                    .padding(horizontal = 12.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = stringResource(R.string.map_locations_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.map_locations_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        Button(onClick = { permissionLauncher.launch(viewModel.permissionToRequest()) }) {
                            Text(stringResource(R.string.map_locations_action))
                        }
                    }
                }
            }
        } else if (!state.hasPins && state.extractionWaiting > 0) {
            MapNotice(
                text = pluralStringResource(
                    R.plurals.map_finding_locations,
                    state.extractionWaiting,
                    state.extractionWaiting,
                ),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        } else if (!state.hasPins && state.placedCount == 0) {
            MapNotice(
                text = stringResource(R.string.map_no_positions),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            )
        }

        if (selected != null) {
            MapPreviewCard(
                pin = requireNotNull(selected),
                previews = previews,
                onOpen = { photo ->
                    viewModel.clearSelection()
                    onOpenMedia(photo)
                },
                onDismiss = viewModel::clearSelection,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        if (strip.isNotEmpty() && selected == null) {
            // The strip is a control, and a control floating over a map needs a backing of its own: without it
            // the bottom edge of the strip is a row of squares whose bounds the eye cannot find against the
            // basemap. It is also lifted clear of the attribution, which is the one line on this screen that
            // must stay readable — and a strip laid over it satisfies the provider's condition of use less
            // well than it satisfies the user's.
            LazyRow(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(
                        start = SpaceSm,
                        end = SpaceSm,
                        bottom = AttributionReserve,
                    )
                    .background(MapNoticeScrim, RoundedCornerShape(StripCorner))
                    .padding(horizontal = SpaceSm, vertical = SpaceSm),
                horizontalArrangement = Arrangement.spacedBy(SpaceXs),
                contentPadding = PaddingValues(horizontal = SpaceXs),
            ) {
                items(items = strip, key = { photo -> photo.mediaStoreId }) { photo ->
                    StripThumbnail(photo = photo, onClick = { onOpenMedia(photo.mediaStoreId) })
                }
            }
        }

        if (viewModel.attribution.isNotBlank()) {
            Text(
                text = viewModel.attribution,
                style = MaterialTheme.typography.labelSmall,
                color = OnMedia,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(MapNoticeScrim)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The pins osmdroid should be showing right now.
 *
 * The overlay list is rebuilt rather than diffed. A cluster's identity is a cell in a grid that changes with
 * every zoom, so keeping markers in sync would mean inventing a key for something that has a useful one only
 * for single photos — and the list is bounded by [MapViewModel.PHOTO_LIMIT] divided into cells, which is
 * hundreds of objects at worst. A diff would be more clever and would not survive a photo being deleted out
 * from under a marker.
 *
 * Every pin is drawn through `setTextIcon`, which is osmdroid's own text-chip route (checked in the artifact's
 * sources: it builds a bitmap the size of the measured text and anchors it at the centre). That is what makes
 * the two kinds of pin one family instead of two scales of the same drawable — the default teardrop is 44 dp
 * of bitmap per photo, and on a view of a city with three hundred photos in it that is a red crowd, not a map.
 * A dot for a photo and a count for a cluster, both from [MarkerStyle.textLabelPx], both in the app's accent.
 */
private fun drawPins(map: MapView, pins: List<MapPin>, viewModel: MapViewModel, style: MarkerStyle) {
    map.overlays.removeAll { it is Marker }

    pins.forEach { pin ->
        val marker = Marker(map)
        marker.position = GeoPoint(pin.latitude, pin.longitude)
        marker.relatedObject = pin
        // A marker with no title announces nothing to the info window or to an accessibility service —
        // the whole map is silent to TalkBack without it. The count is the same words the preview panel
        // uses (`map_cluster_photos`), so a pin and the panel agree about the same spot, and a lone photo
        // reads as "1 photo here" through the same plural. No file name or date: a pin's label is not
        // the place to broadcast somebody's metadata to whatever service is listening.
        marker.title = map.context.resources.getQuantityString(
            R.plurals.map_cluster_photos,
            pin.count,
            pin.count,
        )
        // Set before `setTextIcon`, which reads them as it builds the bitmap.
        marker.setTextLabelBackgroundColor(style.chipBackground)
        marker.setTextLabelForegroundColor(style.chipText)
        marker.setTextLabelFontSize(style.textLabelPx)
        // The count *is* the icon for a cluster: the number is the only thing about it the user needs to read.
        // A single photo carries no number, so it carries a dot — the same chip, emptied of text.
        marker.setTextIcon((pin as? MapPin.Cluster)?.count?.toString() ?: PhotoMark)
        marker.setOnMarkerClickListener { clicked, _ ->
            val target = clicked.relatedObject as? MapPin ?: return@setOnMarkerClickListener false
            when (target) {
                is MapPin.Photo -> viewModel.select(target)
                // Zooming into a cluster rather than listing it: the point of a cluster is that there is too
                // much to show, and a list is the map's own answer to that problem, one level closer.
                is MapPin.Cluster -> {
                    viewModel.clearSelection()
                    map.controller.setCenter(GeoPoint(target.latitude, target.longitude))
                    map.setZoomLevel(
                        (map.zoomLevelDouble + ZOOM_PER_CLUSTER_TAP).coerceAtMost(map.maxZoomLevel),
                    )
                }
            }
            true
        }
        map.overlays.add(marker)
    }
    map.invalidate()
}

/** How a pin is drawn, resolved from the theme and the screen's density away from the overlay code. */
private data class MarkerStyle(
    val chipBackground: Int,
    val chipText: Int,
    val textLabelPx: Int,
)

/**
 * A filled circle, not a bullet.
 *
 * U+25CF is a *geometric* shape whose box is the font size, so it scales with the label exactly as the digits
 * beside it do. U+2022, the typographic bullet, is drawn small and centred high inside the same box — two
 * marks of visibly different sizes on one map, which is the opposite of why this file draws both the same way.
 */
private const val PhotoMark = "●"

/** Reads where the map is looking and tells the model, in the units the query and the clustering need. */
private fun publishViewport(map: MapView, viewModel: MapViewModel) {
    val box = map.boundingBox ?: return
    val zoom = map.zoomLevelDouble
    // Mercator y grows downward and the box osmdroid reports is already north-first, so the only conversion
    // that matters is the antimeridian: a view spanning the whole world must not become a box that inverts.
    val west = box.lonWest
    val east = box.lonEast
    val bounds = if (east >= west) {
        MapBounds(
            minLatitude = box.latSouth,
            maxLatitude = box.latNorth,
            minLongitude = west,
            maxLongitude = east,
        )
    } else {
        // The view crosses ±180. osmdroid's own box cannot express it, so the query widens to the whole
        // globe — matching everything is honest; matching the wrong half of the planet is not.
        MapBounds(-90.0, 90.0, -180.0, 180.0)
    }
    viewModel.viewportChanged(bounds = bounds, zoomLevel = zoom)
}

/**
 * Suspend until the map has been measured, because nothing about a map can be decided before then.
 *
 * `View.post` is not enough and osmdroid says so itself: its own `zoomToBoundingBox` documentation warns that
 * it must be called after the layout is complete, and the view's bounding box is a field that holds a
 * zero-span rectangle until `onLayout` has reset the projection with a real size. A posted runnable can be
 * dispatched at attach, which is before that happens — so this waits for a global layout in which the map
 * actually has a width, and takes its listener back off whether it resolved or was cancelled.
 */
private suspend fun awaitMapMeasured(map: MapView) {
    if (map.width > 0 && map.height > 0) return
    suspendCancellableCoroutine<Unit> { continuation ->
        val observer = map.viewTreeObserver
        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (map.width <= 0 || map.height <= 0) return
                if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                continuation.resume(Unit)
            }
        }
        observer.addOnGlobalLayoutListener(listener)
        // A composition that goes away mid-wait must not leave a listener on a view tree it no longer owns.
        continuation.invokeOnCancellation {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
}

/** How long to leave between the photos and the edge of the screen when the map frames them. */
private val FIT_BORDER = 28.dp

@Composable
private fun MapPreviewCard(
    pin: MapPin,
    previews: List<MapPhoto>,
    onOpen: (Long) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateTime = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val first = previews.firstOrNull() ?: (pin as? MapPin.Photo)?.photo

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (first != null) {
                    StripThumbnail(photo = first, onClick = { onOpen(first.mediaStoreId) }, size = 64.dp)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = first?.let { photo ->
                            photo.dateTakenSeconds?.let { dateTime.format(Date(it * 1000L)) }
                                ?: photo.displayName
                        }.orEmpty(),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (pin.count > 1) {
                        Text(
                            text = pluralStringResource(R.plurals.map_cluster_photos, pin.count, pin.count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.map_preview_close)) }
                first?.let { photo ->
                    Button(onClick = { onOpen(photo.mediaStoreId) }) {
                        Text(stringResource(R.string.map_preview_open))
                    }
                }
            }
        }
    }
}

/**
 * One thumbnail on the map's strip and in its preview card.
 *
 * Decoded to the slot's size by Coil, which is the whole reason the strip can show twenty photos at once: a
 * map viewport is not a place where 48-megapixel originals should be resident. Local `content://` only — the
 * map draws positions from the device, and never fetches a stored original to label a marker.
 */
@Composable
private fun StripThumbnail(
    photo: MapPhoto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = StripSize,
) {
    SubcomposeAsyncImage(
        model = Uri.parse(photo.contentUri),
        contentDescription = photo.displayName,
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(photo.mediaStoreId) { detectTapGestures { onClick() } },
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun MapNotice(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(horizontal = 12.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** Photos the preview card can show behind a cluster. */
private const val PREVIEW_LIMIT = 4

/** How much closer a tap on a cluster gets. Two levels separates a city from its streets in one gesture. */
private const val ZOOM_PER_CLUSTER_TAP = 2.0

/** A pan is continuous; the query should not be. */
private const val VIEWPORT_DEBOUNCE_MILLIS = 250L

/** Deep enough to recognise a place, shallow enough that a cluster has something to separate into. */
private const val FOCUS_ZOOM = 12.0

/**
 * A pin's label, in dp so it scales with density rather than with the user's font setting.
 *
 * `setTextLabelFontSize` takes pixels, and its default is 24 of them — on a 3x phone that is 8 dp of text for
 * a cluster's two digits, and the same call draws a single photo's mark, which is why one number here sets
 * both. The default *teardrop* it replaces was 44 dp tall per photo.
 */
private val MarkerLabelSize = 14.dp

/** A strip small enough to read as a strip and large enough to tap: 56 dp is a target, not a thumbnail wall. */
private val StripSize = 56.dp
private val StripCorner = 12.dp

/** How much of the bottom edge belongs to the attribution line, so the strip is lifted above it. */
private val AttributionReserve = 22.dp

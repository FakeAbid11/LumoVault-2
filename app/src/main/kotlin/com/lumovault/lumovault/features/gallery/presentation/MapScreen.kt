package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * Photo map: every scanned item with GPS coordinates as a pin over
 * OpenStreetMap tiles.
 *
 * Ported from lib/features/gallery/presentation/screens/map_screen.dart with
 * two deliberate deviations:
 *
 *  - **No marker clustering.** The original used flutter_map_marker_cluster;
 *    osmdroid has no equivalent bundled, and adding a clustering library is a
 *    follow-up. Plain markers are correct for moderate libraries.
 *  - **Markers are diffed, not rebuilt per frame.** The original's perf
 *    defect — rebuilding the whole marker list whenever the stream emitted —
 *    is not ported: the overlay set is only touched when the photo list
 *    identity changes (see the AndroidView update block).
 *
 * There is no "center on my location" button: the device-location stack
 * (geolocator's equivalent) is not a declared dependency. The camera centers
 * on the first located photo instead.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onBack: () -> Unit,
    onOpenItem: (index: Int, items: List<MediaItemEntity>) -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val photos by viewModel.located.collectAsStateWithLifecycle()
    var mapView by remember { mutableStateOf<MapView?>(null) }

    // osmdroid's MapView needs the two lifecycle events Compose cannot infer.
    val map = mapView
    if (map != null) {
        DisposableEffect(map) {
            map.onResume()
            onDispose { map.onPause() }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.map_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            OsmPhotoMap(
                photos = photos,
                onPhotoClick = { index -> onOpenItem(index, photos) },
                onMapReady = { mapView = it },
            )
            if (photos.isEmpty()) {
                EmptyCollection(
                    title = stringResource(R.string.map_empty_title),
                    explanation = stringResource(R.string.map_empty_explanation),
                )
            }
        }
    }
}
@Composable
private fun OsmPhotoMap(
    photos: List<MediaItemEntity>,
    onPhotoClick: (Int) -> Unit,
    onMapReady: (MapView) -> Unit,
) {
    // Identity of the last list whose markers were applied — the guard that
    // keeps recompositions from rebuilding the overlay set.
    val appliedPhotos = remember { arrayOfNulls<List<MediaItemEntity>>(1) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            Configuration.getInstance().load(
                ctx,
                ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE),
            )
            Configuration.getInstance().userAgentValue = ctx.packageName
            MapView(ctx).apply {
                setTileSource(TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(2.0)
                controller.setCenter(GeoPoint(0.0, 0.0))
                onMapReady(this)
            }
        },
        update = { map ->
            // Skip the rebuild when only recomposition happened; the photo
            // list identity is the diff key.
            if (appliedPhotos[0] === photos) return@AndroidView
            appliedPhotos[0] = photos

            map.overlays.removeAll { it is Marker }
            var centered = false
            photos.forEachIndexed { index, item ->
                val lat = item.latitude ?: return@forEachIndexed
                val lng = item.longitude ?: return@forEachIndexed
                val point = GeoPoint(lat, lng)
                if (!centered) {
                    map.controller.setZoom(12.0)
                    map.controller.setCenter(point)
                    centered = true
                }
                map.overlays.add(
                    Marker(map).apply {
                        position = point
                        title = item.locationName ?: item.fileName
                        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                        setOnMarkerClickListener { _, _ ->
                            onPhotoClick(index)
                            true
                        }
                    },
                )
            }
            map.invalidate()
        },
        onRelease = { it.onPause() },
    )
}


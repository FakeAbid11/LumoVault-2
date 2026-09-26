package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MediaLocation

/**
 * The rectangle the library's positioned photos occupy, as the metadata table reports it.
 *
 * Nullable members rather than a nullable row, because SQL answers `MIN(latitude)` over zero rows with a row
 * of nulls: "the query ran and there is nothing placed" is a fact worth keeping distinct from "the query
 * failed", and the map draws a different sentence for each.
 */
data class LocatedBounds(
    val minLatitude: Double?,
    val maxLatitude: Double?,
    val minLongitude: Double?,
    val maxLongitude: Double?,
    val count: Int,
)

/** Where the map should be when it is first shown. */
sealed interface MapPlacement {
    /** Widen and move the view until the whole set of positions fits inside it. */
    data class Fit(val bounds: MapBounds) : MapPlacement

    /**
     * Put one place in the middle, at a zoom that recognises it.
     *
     * The answer to a set of positions that occupies no area — a single photo, or fifty taken in one room. A
     * bounding box with no span cannot be fitted to anything: asking the widget to fit one asks for the
     * deepest zoom there is, which is a street nobody is looking for.
     */
    data class Centre(val at: MediaLocation, val zoom: Double) : MapPlacement

    /** Leave the map where it is. */
    data object Leave : MapPlacement
}

/**
 * The map's opening framing, decided without a map.
 *
 * A `MapView` can only be asked for its bounding box after it has been laid out, and it can only be *told*
 * where to open when it has — which makes the whole first frame of the screen a race between a database query,
 * a layout pass and a widget that reports a zero-sized box until then. Every judgement that race needs is
 * therefore made here, over numbers: what a set of positions frames to, whether that is a box or a point, and
 * how many times the map may move itself before it stops moving (once).
 *
 * The one thing this file refuses is the second framing. A map that recentres on the library whenever the
 * viewport changes is a map the user cannot move: they pan away, the query answers, and the view snaps back.
 */
object MapFraming {
    /**
     * A span this narrow is not an area to fit.
     *
     * 0.01° is a little over a kilometre at the equator and still tens of metres across in most of a
     * populated country's latitudes — well inside what one zoom level shows, so a box smaller than it is a
     * place, not a region.
     */
    const val MIN_FIT_SPAN_DEGREES = 0.01

    /**
     * The zoom a single place opens at.
     *
     * Deep enough to read a street, shallower than [MapClustering]'s maximum so a person who took one photo in
     * a city can still pan out to find the next one.
     */
    const val SINGLE_PLACE_ZOOM = 13.0

    /** The framing the library earns, from what the metadata table says it holds. */
    fun placementFor(bounds: LocatedBounds?): MapPlacement {
        val box = bounds ?: return MapPlacement.Leave
        val minLat = box.minLatitude ?: return MapPlacement.Leave
        val maxLat = box.maxLatitude ?: return MapPlacement.Leave
        val minLon = box.minLongitude ?: return MapPlacement.Leave
        val maxLon = box.maxLongitude ?: return MapPlacement.Leave
        if (box.count <= 0) return MapPlacement.Leave

        val rectangle = MapBounds(
            minLatitude = minLat,
            maxLatitude = maxLat,
            minLongitude = minLon,
            maxLongitude = maxLon,
        )
        // Inverted or out-of-range bounds are not a place to open a map at. `MediaLocation` would throw on
        // them and the map has no business throwing over one bad row: the answer is to draw nothing and let
        // the screen's own notice say what it sees.
        if (!rectangle.isValid || !rectangle.isWithinWorld) return MapPlacement.Leave

        val spansAnArea = maxLat - minLat > MIN_FIT_SPAN_DEGREES || maxLon - minLon > MIN_FIT_SPAN_DEGREES
        return if (spansAnArea) {
            MapPlacement.Fit(rectangle)
        } else {
            // Midpoints, not a corner: a single place should sit in the middle of the screen.
            MapPlacement.Centre(
                at = MediaLocation((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0),
                zoom = SINGLE_PLACE_ZOOM,
            )
        }
    }

    /**
     * Whether the map may move itself now.
     *
     * Framing is a one-shot: the first placement happens, and every later viewport is the user's. Without
     * this the map fights a pan — the pan publishes a viewport, the viewport re-reads the positions, and
     * "position the map on the photos" arrives again with a perfectly good answer.
     */
    fun shouldPlace(alreadyPlaced: Boolean, placement: MapPlacement): Boolean =
        !alreadyPlaced && placement != MapPlacement.Leave

    /** The whole world, which is what a viewport spanning ±180 asks the query for. */
    private val MapBounds.isWithinWorld: Boolean
        get() = minLatitude >= -90.0 && maxLatitude <= 90.0 && minLongitude >= -180.0 && maxLongitude <= 180.0
}

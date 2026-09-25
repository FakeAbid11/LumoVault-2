package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MapPhoto
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Web Mercator, in world pixels — the arithmetic clustering needs, kept out of the map widget.
 *
 * osmdroid draws the map and owns what is on screen. Computing positions here as well is not duplication, it
 * is testability: "are these two photos close enough to be one marker" is a question in *pixel* space at the
 * current zoom, and a version of it that can only be asked of a laid-out `MapView` cannot be asserted about
 * anywhere a build server can reach.
 *
 * The formulas are the standard slippy-map ones for a 256-pixel tile — x linear in longitude, y the Mercator
 * latitude function — with latitude clamped to ±85.0511287…, which is not a courtesy: the function diverges at
 * the poles, so a latitude beyond that has no pixel to land on.
 */
object WebMercator {
    /** Tile edge in pixels, as every slippy-map provider defines it. */
    const val TILE_SIZE = 256

    /** The highest latitude this projection can place. */
    const val MAX_LATITUDE = 85.0511287798066

    /** The deepest zoom worth asking about; providers stop at 19 or 21 and osmdroid caps at 22. */
    const val MAX_ZOOM = 22

    fun worldSize(zoom: Int): Double = TILE_SIZE * Math.pow(2.0, zoom.coerceIn(0, MAX_ZOOM).toDouble())

    fun xOf(longitude: Double, zoom: Int): Double =
        worldSize(zoom) * (normalizeLongitude(longitude) + 180.0) / 360.0

    fun yOf(latitude: Double, zoom: Int): Double {
        val phi = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return worldSize(zoom) * (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
    }

    fun longitudeOf(x: Double, zoom: Int): Double =
        normalizeLongitude(x / worldSize(zoom) * 360.0 - 180.0)

    /** The inverse of [yOf], written with `sinh` because that is the form that does not diverge near a pole. */
    fun latitudeOf(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * (y / worldSize(zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Back into -180..180, which any arithmetic needs as soon as a map repeats horizontally. */
    fun normalizeLongitude(longitude: Double): Double {
        var value = longitude
        while (value > 180.0) value -= 360.0
        while (value < -180.0) value += 360.0
        return value
    }

    /** The signed shortest gap between two longitudes, across the antimeridian if it must. */
    fun longitudeGap(from: Double, to: Double): Double {
        val direct = normalizeLongitude(to) - normalizeLongitude(from)
        return when {
            direct > 180.0 -> direct - 360.0
            direct < -180.0 -> direct + 360.0
            else -> direct
        }
    }

    /** Distance in world pixels at [zoom], which is the unit a marker's footprint is measured in. */
    fun pixelDistance(
        latitudeA: Double,
        longitudeA: Double,
        latitudeB: Double,
        longitudeB: Double,
        zoom: Int,
    ): Double {
        val dx = xOf(longitudeA, zoom) - xOf(longitudeB, zoom)
        val dy = yOf(latitudeA, zoom) - yOf(latitudeB, zoom)
        return sqrt(dx * dx + dy * dy)
    }
}

/**
 * Something to put on the map: one photo, or a count standing in for several.
 *
 * Two types rather than a `count > 1` test, because they need different handling everywhere else — a tap on a
 * photo opens the photo and a tap on a cluster zooms the map — and a condition repeated across the screen is
 * how one of the two eventually gets the wrong branch.
 */
sealed interface MapPin {
    val latitude: Double
    val longitude: Double
    val mediaStoreIds: List<Long>
    val count: Int

    data class Photo(val photo: MapPhoto) : MapPin {
        override val latitude: Double get() = photo.latitude
        override val longitude: Double get() = photo.longitude
        override val mediaStoreIds: List<Long> get() = listOf(photo.mediaStoreId)
        override val count: Int get() = 1
    }

    data class Cluster(
        override val latitude: Double,
        override val longitude: Double,
        override val mediaStoreIds: List<Long>,
    ) : MapPin {
        override val count: Int get() = mediaStoreIds.size
    }
}

/**
 * Grid bucketing in world pixels, at the zoom the user is looking at.
 *
 * Deliberately simple, and simple for a reason: a geohash or an R-tree is the more sophisticated answer to a
 * question that is not hard, and either would make the rule harder to read than the rule. Photos fall into the
 * cell their projected position lands in; a cell with one photo is a marker and a cell with several is a
 * cluster; and a cluster sits at the mean of its members' *projected* positions, unprojected — averaging
 * latitudes directly drifts toward the poles, which at high latitudes is a marker in the wrong place.
 *
 * Cells are never merged into bigger cells. That is what the zoom is for: the caller re-runs this on every
 * zoom change, so zooming out produces larger buckets by itself and zooming in takes them apart again, which
 * is the "progressively reveal more precise clusters" of PRD section 31 with one fewer data structure to keep
 * in sync.
 *
 * Longitude is normalised before bucketing, so two photos a metre apart on either side of ±180 share a cell.
 * Without that, a cluster splits in half at the map's own seam — not an edge case in the Pacific.
 */
object MapClustering {

    /** Photos within this many pixels of each other become one marker; roughly a marker's own footprint. */
    const val CELL_PIXELS = 64

    /** How far the zoom is allowed to go, and the ceiling every helper here shares. */
    const val MAX_ZOOM = WebMercator.MAX_ZOOM

    /** The zoom a first map view opens at: a country, not the world and not a city. */
    const val DEFAULT_ZOOM = 5

    fun zoomOf(zoomLevel: Double): Int = zoomLevel.roundToInt().coerceIn(0, MAX_ZOOM)

    fun cluster(
        photos: List<MapPhoto>,
        zoom: Int,
        cellPixels: Int = CELL_PIXELS,
    ): List<MapPin> {
        if (photos.isEmpty()) return emptyList()
        val cell = max(1, cellPixels)

        val buckets = LinkedHashMap<Pair<Long, Long>, MutableList<MapPhoto>>()
        photos.forEach { photo ->
            val key = Pair(
                Math.floor(WebMercator.xOf(photo.longitude, zoom) / cell).toLong(),
                Math.floor(WebMercator.yOf(photo.latitude, zoom) / cell).toLong(),
            )
            buckets.getOrPut(key) { mutableListOf() }.add(photo)
        }

        val newest = photos.associate { it.mediaStoreId to (it.dateTakenSeconds ?: it.dateAddedSeconds) }

        return buckets.values.map { members ->
            if (members.size == 1) {
                MapPin.Photo(members.single())
            } else {
                MapPin.Cluster(
                    latitude = meanLatitude(members, zoom),
                    longitude = meanLongitude(members, zoom),
                    mediaStoreIds = members.map(MapPhoto::mediaStoreId).sorted(),
                )
            }
        }.sortedWith(
            // Smallest first. osmdroid paints overlays in list order, so the last marker added is the one on
            // top — and a bubble that says 400 has to cover the pin it overlaps, not hide behind it. Newest
            // capture breaks ties so a pan does not reorder pins under the finger, and the id breaks the rest
            // because it is the only remaining tie that is stable.
            compareBy<MapPin> { it.count }
                .thenByDescending { pin -> pin.mediaStoreIds.maxOfOrNull(newest::get) ?: 0L }
                .thenBy { pin -> pin.mediaStoreIds.minOrNull() ?: 0L },
        )
    }

    /**
     * The rectangle a viewport of [widthPx] x [heightPx] covers, centred on a position.
     *
     * Mercator y grows downward, so the top of the screen is the *larger* latitude and the smaller y. Getting
     * that inversion wrong puts a query box on the other side of the equator, which is why the corners are
     * sorted after the conversion instead of trusting a guessed order.
     *
     * A viewport wider than half the world stops being a window and becomes the whole map: the box is clamped
     * rather than wrapped, because a longitude range that passes 180° would match every photo on the device and
     * a map that quietly does that is showing the user a lie about what is on screen.
     */
    fun boundsFor(
        centreLatitude: Double,
        centreLongitude: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
    ): MapBounds {
        val world = WebMercator.worldSize(zoom)
        val centreX = WebMercator.xOf(centreLongitude, zoom)
        val centreY = WebMercator.yOf(centreLatitude, zoom)
        val halfWidth = min(widthPx / 2.0, world / 2.0)
        val halfHeight = min(heightPx / 2.0, world / 2.0)

        val top = WebMercator.latitudeOf(centreY - halfHeight, zoom)
        val bottom = WebMercator.latitudeOf(centreY + halfHeight, zoom)
        val westX = centreX - halfWidth
        val eastX = centreX + halfWidth
        val spansWorld = eastX - westX >= world

        return MapBounds(
            minLatitude = min(top, bottom).coerceIn(-90.0, 90.0),
            maxLatitude = max(top, bottom).coerceIn(-90.0, 90.0),
            minLongitude = if (spansWorld) -180.0 else WebMercator.longitudeOf(westX, zoom),
            maxLongitude = if (spansWorld) 180.0 else WebMercator.longitudeOf(eastX, zoom),
        )
    }

    private fun meanLatitude(members: List<MapPhoto>, zoom: Int): Double {
        val meanY = members.sumOf { WebMercator.yOf(it.latitude, zoom) } / members.size
        return WebMercator.latitudeOf(meanY, zoom).coerceIn(-90.0, 90.0)
    }

    private fun meanLongitude(members: List<MapPhoto>, zoom: Int): Double {
        val meanX = members.sumOf { WebMercator.xOf(it.longitude, zoom) } / members.size
        return WebMercator.longitudeOf(meanX, zoom)
    }
}

/**
 * Where the map is looking.
 *
 * The bounds and the zoom travel together because clustering needs the zoom and the query needs the bounds,
 * and a change to one without the other is a stale answer: the same rectangle at a different zoom should
 * produce different pins, and would not if only the rectangle were compared.
 */
data class MapViewport(val bounds: MapBounds, val zoom: Int)

package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MapPhoto
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Web Mercator, in pixels — the arithmetic the map's clustering needs, kept away from the map widget.
 *
 * osmdroid draws the map and owns what is on screen. It is still worth computing positions here, because the
 * decision *"are these two photos close enough to be one marker"* has to be made in pixel space at the current
 * zoom, and a version of that decision that can only be asked of a laid-out view cannot be tested at all. This
 * is the standard slippy-map formulation: a 256-pixel tile, a world of `256 · 2^z` pixels, x linear in
 * longitude, and y the Mercator latitude function.
 *
 * Latitude is clamped to ±85.0511…°, which is not a courtesy: the function diverges at the poles, and a
 * latitude past that limit has no pixel to land on.
 */
object WebMercator {
    /** Tile edge in pixels, as every slippy-map provider defines it. */
    const val TILE_SIZE = 256

    /** The highest latitude the projection can place. */
    const val MAX_LATITUDE = 85.0511287798066

    /** Zoom osmdroid and every OSM-derived provider stop at, and the ceiling used here. */
    const val MAX_ZOOM = 22

    fun worldSize(zoom: Int): Double = TILE_SIZE * 2.0.pow(zoom.coerceIn(0, MAX_ZOOM))

    fun xOf(longitude: Double, zoom: Int): Double =
        worldSize(zoom) * (normalizeLongitude(longitude) + 180.0) / 360.0

    fun yOf(latitude: Double, zoom: Int): Double {
        val phi = Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE))
        return worldSize(zoom) * (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0
    }

    fun longitudeOf(x: Double, zoom: Int): Double = normalizeLongitude(x / worldSize(zoom) * 360.0 - 180.0)

    /** The inverse of [yOf], written with `sinh` because it is the form that does not overflow near the poles. */
    fun latitudeOf(y: Double, zoom: Int): Double {
        val n = PI - 2.0 * PI * (y / worldSize(zoom))
        return Math.toDegrees(atan(sinh(n)))
    }

    /** Back into -180..180, which the arithmetic needs as soon as a map repeats horizontally. */
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

    /** Distance in world pixels at [zoom]; the unit a marker's footprint is measured in. */
    fun pixelDistance(latitudeA: Double, longitudeA: Double, latitudeB: Double, longitudeB: Double, zoom: Int): Double {
        val dx = xOf(longitudeA, zoom) - xOf(longitudeB, zoom)
        val dy = yOf(latitudeA, zoom) - yOf(latitudeB, zoom)
        return sqrt(dx * dx + dy * dy)
    }

    private fun cos(value: Double): Double = Math.cos(value)

    private fun Double.pow(exponent: Int): Double = Math.pow(this, exponent.toDouble())
}

/**
 * Something to put on the map: one photo, or a count standing in for several.
 *
 * Carried as two types rather than a `count > 1` test because they need different handling everywhere else —
 * a tap on a photo opens it and a tap on a cluster zooms — and a boolean condition spread across the screen is
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
 * Deliberately simple, and simple for a reason. A geohash or an R-tree is the more sophisticated answer and is
 * not needed to stop two thousand overlapping markers being drawn, while both would make the rule harder to
 * read than the rule itself. Photos fall into the cell their projected position lands in; a cell with one
 * photo is a marker and a cell with several is a cluster; and a cluster sits at the mean of its members'
 * *projected* positions, unprojected — averaging latitudes and longitudes directly drifts toward the poles and,
 * at high latitudes, puts a marker in the wrong place.
 *
 * Nothing here merges cells into bigger cells. That is what the zoom level is for: the caller re-runs this on
 * every zoom change, so zooming out produces larger buckets naturally and zooming in takes them apart again,
 * which is the behaviour PRD section 31 asks for ("progressively reveal more precise clusters") and one fewer
 * data structure to keep in sync.
 *
 * Longitude is normalised before bucketing, so two photos a metre apart on either side of ±180 land in the
 * same cell. Without it a cluster splits in half at the map's own seam — not an edge case in the Pacific.
 */
object MapClustering {

    /** Photos within this many pixels of each other become one marker. Roughly a marker's own footprint. */
    const val DEFAULT_CELL_PIXELS = 64

    /** A cluster may stand for before the caller stops asking for its members; see [MAX_CLUSTER_PHOTOS]. */
    const val MAX_CLUSTER_PHOTOS = 400

    fun cluster(
        photos: List<MapPhoto>,
        zoom: Int,
        cellPixels: Int = DEFAULT_CELL_PIXELS,
    ): List<MapPin> {
        if (photos.isEmpty()) return emptyList()
        val cell = max(1, cellPixels)

        val buckets = LinkedHashMap<Pair<Long, Long>, MutableList<MapPhoto>> ()
        photos.forEach { photo ->
            val key = Pair(
                Math.floor(WebMercator.xOf(photo.longitude, zoom) / cell).toLong(),
                Math.floor(WebMercator.yOf(photo.latitude, zoom) / cell).toLong(),
            )
            buckets.getOrPut(key) { mutableListOf() }.add(photo)
        }

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
            // Biggest first, so a marker for 400 photos is drawn over one for two, then newest capture so the
            // order does not shuffle under a pan. The id breaks the remaining ties for the same reason.
            compareByDescending<MapPin> { it.count }
                .thenByDescending { pin -> newestCaptureOf(pin, photos) }
                .thenBy { pin -> pin.mediaStoreIds.minOrNull() ?: 0L },
        )
    }

    /** The integer zoom a map shows, from the fractional level osmdroid reports. */
    fun zoomOf(zoomLevel: Double): Int = zoomLevel.roundToInt().coerceIn(0, WebMercator.MAX_ZOOM)

    /**
     * How many pixels a marker's cell should span at this zoom.
     *
     * A fixed pixel cell is the whole trick: the world grows by a factor of four per zoom level, so the same
     * 64-pixel cell covers a city at zoom 8 and a street at zoom 17 without any change of parameters.
     */
    const val MARKER_CELL_PIXELS = DEFAULT_CELL_PIXELS

    private fun meanLatitude(members: List<MapPhoto>, zoom: Int): Double {
        val meanY = members.sumOf { WebMercator.yOf(it.latitude, zoom) } / members.size
        return WebMercator.latitudeOf(meanY, zoom).coerceIn(-90.0, 90.0)
    }

    private fun meanLongitude(members: List<MapPhoto>, zoom: Int): Double {
        val meanX = members.sumOf { WebMercator.xOf(it.longitude, zoom) } / members.size
        return WebMercator.longitudeOf(meanX, zoom)
    }

    private fun newestCaptureOf(pin: MapPin, photos: List<MapPhoto>): Long {
        val ids = pin.mediaStoreIds.toSet()
        return photos.asSequence()
            .filter { it.mediaStoreId in ids }
            .maxOfOrNull { it.dateTakenSeconds ?: it.dateAddedSeconds }
            ?: 0L
    }

    /**
     * The rectangle a viewport of [widthPx] x [heightPx] covers, centred on a position.
     *
     * Used to ask Room for the photos on screen. Mercator y increases downward, so the top half of the
     * viewport is the *higher* latitude and the smaller y — getting that inversion wrong is what puts a query
     * box on the other side of the equator, and it is asserted in the tests for exactly that reason.
     *
     * A viewport wider than the world is clamped to it rather than wrapped, because a query that spans more
     * than 360° of longitude would match every photo on the device and is a bug, not a zoom level.
     */
    fun boundsFor(
        centreLatitude: Double,
        centreLongitude: Double,
        zoom: Int,
        widthPx: Int,
        heightPx: Int,
    ): com.lumovault.app.domain.model.MapBounds {
        val centreX = WebMercator.xOf(centreLongitude, zoom)
        val centreY = WebMercator.yOf(centreLatitude, zoom)
        val world = WebMercator.worldSize(zoom)
        val halfWidth = min(widthPx / 2.0, world / 2.0)
        val halfHeight = min(heightPx / 2.0, world / 2.0)

        val north = WebMercator.latitudeOf(centreY - halfHeight, zoom)
        val south = WebMercator.latitudeOf(centreY + halfHeight, zoom)
        val west = WebMercator.longitudeOf(centreX - halfWidth, zoom)
        val east = WebMercator.longitudeOf(centreX + halfWidth, zoom)

        val minLongitude: Double
        val maxLongitude: Double
        if (WebMercator.longitudeGap(west, east) < 0.0) {
            minLongitude = -180.0
            maxLongitude = 180.0
        } else {
            minLongitude = west
            maxLongitude = east
        }

        return com.lumovault.app.domain.model.MapBounds(
            minLatitude = min(south, north).coerceIn(-90.0, 90.0),
            maxLatitude = max(south, north).coerceIn(-90.0, 90.0),
            minLongitude = minLongitude,
            maxLongitude = maxLongitude,
        )
    }
}

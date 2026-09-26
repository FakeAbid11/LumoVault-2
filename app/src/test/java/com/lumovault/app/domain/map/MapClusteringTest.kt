package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MapPhoto
import com.lumovault.app.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map's arithmetic, which is the part a device cannot be trusted to check.
 *
 * A wrong tile looks like a slow network; a wrong cluster looks like a plausible map. Neither announces
 * itself, and both are pure functions of numbers, so they are tested here rather than hoped about. The
 * reference values are the slippy-map ones: at zoom 0 the whole world is one 256-pixel tile, so 0° longitude
 * is x 128 and 0° latitude is y 128, and those anchors catch a flipped or off-by-one tile grid immediately.
 */
class MapClusteringTest {

    @Test
    fun theZoomZeroTileIsTheWholeWorld() {
        assertEquals(256.0, WebMercator.worldSize(0), 0.0)
        assertEquals(128.0, WebMercator.xOf(0.0, zoom = 0), 0.001)
        assertEquals(128.0, WebMercator.yOf(0.0, zoom = 0), 0.001)
        assertEquals(0.0, WebMercator.xOf(-180.0, zoom = 0), 0.001)
        assertEquals(256.0, WebMercator.xOf(180.0, zoom = 0), 0.001)
    }

    @Test
    fun aPositionSurvivesAProjectionRoundTrip() {
        listOf(
            52.5200 to 13.4050,
            -6.9147 to 107.6098,
            23.8103 to 90.4125,
            64.1353 to -21.8952,
            -33.8688 to 151.2093,
        ).forEach { (latitude, longitude) ->
            val zoom = 9
            assertEquals(
                "latitude $latitude",
                latitude,
                WebMercator.latitudeOf(WebMercator.yOf(latitude, zoom), zoom),
                0.0001,
            )
            assertEquals(
                "longitude $longitude",
                longitude,
                WebMercator.longitudeOf(WebMercator.xOf(longitude, zoom), zoom),
                0.0001,
            )
        }
    }

    @Test
    fun thePolesAreOutsideTheProjectionAndAreClampedNotCrashed() {
        // 90° has no pixel. It has to land somewhere finite, or a single malformed GPS tag ends the map.
        assertTrue(WebMercator.yOf(90.0, zoom = 4).isFinite())
        assertTrue(WebMercator.yOf(-90.0, zoom = 4).isFinite())
        assertEquals(WebMercator.yOf(WebMercator.MAX_LATITUDE, zoom = 4), WebMercator.yOf(90.0, zoom = 4), 0.001)
    }

    @Test
    fun longitudesWrapInsteadOfLeavingTheScale() {
        assertEquals(-170.0, WebMercator.normalizeLongitude(190.0), 0.001)
        assertEquals(170.0, WebMercator.normalizeLongitude(-190.0), 0.001)
        assertEquals(
            "the two sides of the antimeridian are neighbours, and the map has to know it",
            0.001,
            WebMercator.longitudeGap(179.9995, -179.9995),
            0.0001,
        )
        assertEquals(-0.001, WebMercator.longitudeGap(-179.9995, 179.9995), 0.0001)
    }

    @Test
    fun photosThatOverlapAtThisZoomAreOneMarker() {
        val near = photo(1L, 52.5200, 13.4050)
        val alsoNear = photo(2L, 52.5201, 13.4051)
        val far = photo(3L, 48.8566, 2.3522)

        val pins = MapClustering.cluster(listOf(near, alsoNear, far), zoom = 5)

        assertEquals(
            "at a world view, Berlin and Paris are one marker and their two photos are not three",
            listOf(1, 2),
            pins.map { it.count }.sorted(),
        )
        val cluster = pins.filterIsInstance<MapPin.Cluster>().single()
        assertEquals(listOf(1L, 2L), cluster.mediaStoreIds)
    }

    @Test
    fun photosAPartAtOneZoomAreApartAtAnother() {
        // 0.05° of longitude is ~3.4 km at this latitude: 9 pixels at zoom 8 (one 64-px cell) and 580 at zoom
        // 14 (nine cells). Both numbers come from worldSize = 256·2^z rather than from a guess, because the
        // pair this test first used was 11 m apart — five pixels at zoom 16, which one cell *should* merge, so
        // the original expectation was testing the comment rather than the rule.
        val west = photo(1L, 52.52, 13.405)
        val east = photo(2L, 52.52, 13.455)

        assertEquals(
            "close enough on screen is the whole definition of a cluster, and at this zoom it is one bubble",
            1,
            MapClustering.cluster(listOf(west, east), zoom = 8).size,
        )
        assertEquals(
            "the same two photos, one tap-zoom deeper, are two markers — which is what a fixed pixel cell " +
                "gives for free, with no merge ladder to maintain",
            2,
            MapClustering.cluster(listOf(west, east), zoom = 14).size,
        )
    }

    @Test
    fun aClusterSitsAtTheMercatorMeanAndNotTheAverageOfTheTwoLatitudes() {
        // Mercator stretches toward the poles, so the middle of a cell is not the arithmetic mean of its
        // latitudes. The difference only becomes visible where a cell covers tens of degrees — at zoom 1 a
        // 64-pixel cell runs from the equator to ~41° S — which is exactly the zoom at which a misplaced
        // bubble is a bubble in the wrong country.
        val equator = photo(1L, 0.0, 13.4)
        val south = photo(2L, -40.0, 13.4)

        val cluster = MapClustering.cluster(listOf(equator, south), zoom = 1)
            .filterIsInstance<MapPin.Cluster>()
            .single()

        assertTrue(
            "the average of 0 and -40 is -20, which is not the middle of these two on the screen; " +
                "got ${cluster.latitude}",
            cluster.latitude < -20.5,
        )
        assertEquals(13.4, cluster.longitude, 0.001)
    }

    @Test
    fun photosOnEitherSideOfTheSeamAreBothPlacedAndNeitherMovesToTheMiddle() {
        val east = photo(1L, 0.0, 179.999)
        val west = photo(2L, 0.0, -179.999)

        val pins = MapClustering.cluster(listOf(east, west), zoom = 3)

        assertEquals(
            "a cell boundary falls at the world's edge as it does everywhere else, so these are two markers",
            2,
            pins.size,
        )
        pins.forEach { pin ->
            assertTrue(
                "what must not happen is either of them being averaged toward 0°, which is the Gulf of " +
                    "Guinea; got ${pin.longitude}",
                kotlin.math.abs(pin.longitude) > 179.0,
            )
        }
    }

    @Test
    fun theBiggestMarkerIsDrawnLastSoItLiesOnTop() {
        val many = (1L..5L).map { photo(it, 52.52, 13.40) }
        val few = (10L..11L).map { photo(it, 48.85, 2.35) }
        val lonely = photo(20L, -33.86, 151.21)

        val pins = MapClustering.cluster(many + few + listOf(lonely), zoom = 5)

        assertEquals(
            "smallest first, because osmdroid paints in list order and the five-photo bubble has to end up on " +
                "top rather than buried under a single pin",
            listOf(1, 2, 5),
            pins.map { it.count },
        )
    }

    @Test
    fun nothingToPlaceIsNoPinsAndNotAnError() {
        assertEquals(0, MapClustering.cluster(emptyList(), zoom = 4).size)
    }

    @Test
    fun theTopOfTheScreenIsTheBiggerLatitude() {
        // Mercator y grows downward, so the smaller y is the north. Getting this backwards puts every query
        // box in the southern hemisphere and shows a map that looks like it has no photos.
        val bounds = MapClustering.boundsFor(
            centreLatitude = 52.52,
            centreLongitude = 13.40,
            zoom = 10,
            widthPx = 1080,
            heightPx = 1920,
        )

        assertTrue(bounds.minLatitude < bounds.maxLatitude)
        assertTrue("the centre is inside the box it produced", bounds.minLatitude < 52.52 && 52.52 < bounds.maxLatitude)
        assertTrue(bounds.minLongitude < 13.40 && 13.40 < bounds.maxLongitude)
    }

    @Test
    fun aViewportWiderThanTheWorldBecomesTheWorld() {
        val bounds = MapClustering.boundsFor(
            centreLatitude = 0.0,
            centreLongitude = 0.0,
            zoom = 0,
            widthPx = 10_000,
            heightPx = 10_000,
        )

        // A box that wrapped past 360° would match every photo on the device — a bug wearing the costume of a
        // zoomed-out map — so the longitude span stops at the globe rather than continuing around it.
        assertEquals(-180.0, bounds.minLongitude, 0.001)
        assertEquals(180.0, bounds.maxLongitude, 0.001)
        assertTrue("and latitudes stay inside the range they name", bounds.minLatitude <= bounds.maxLatitude)
    }

    @Test
    fun aFractionalZoomBecomesAUsableOne() {
        assertEquals(5, MapClustering.zoomOf(5.4))
        assertEquals(6, MapClustering.zoomOf(5.6))
        assertEquals(0, MapClustering.zoomOf(-3.0))
        assertEquals(MapClustering.MAX_ZOOM, MapClustering.zoomOf(99.0))
    }

    @Test
    fun aSinglePhotoIsAMarkerAndNotACount() {
        val pins = MapClustering.cluster(listOf(photo(1L, 52.52, 13.40)), zoom = 12)

        assertEquals(1, pins.size)
        assertTrue(
            "one photo is not a cluster of one, and the tap has to know the difference",
            pins.single() is MapPin.Photo,
        )
    }

    private fun photo(id: Long, latitude: Double, longitude: Double) = MapPhoto(
        mediaStoreId = id,
        contentUri = "content://media/external/images/media/$id",
        type = MediaType.Photo,
        displayName = "IMG_$id.jpg",
        latitude = latitude,
        longitude = longitude,
        dateTakenSeconds = 1_700_000_000L + id,
        dateAddedSeconds = 1_700_000_000L,
    )
}

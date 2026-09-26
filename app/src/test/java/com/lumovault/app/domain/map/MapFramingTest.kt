package com.lumovault.app.domain.map

import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MediaLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the map should open, asserted without a map.
 *
 * Every case here exists because the alternative is a screen that shows nothing and can say nothing about
 * why: a map opened at the default centre with a library in another country has no markers in its viewport,
 * so the query returns an empty list, so the pins list is empty, so the map looks broken in exactly the way a
 * working map looks when a user has no GPS in their photos. None of that can be seen in a unit test through
 * osmdroid — a `MapView` needs a display, a layout pass and a size — so the decisions are made here, over
 * numbers, and the screen is left with nothing to decide but to obey them once.
 */
class MapFramingTest {
    private fun placed(
        minLatitude: Double?,
        maxLatitude: Double?,
        minLongitude: Double?,
        maxLongitude: Double?,
        count: Int = 2,
    ) = LocatedBounds(minLatitude, maxLatitude, minLongitude, maxLongitude, count)

    @Test
    fun aLibraryWithNothingPlacedYetLeavesTheMapWhereItIs() {
        assertEquals(MapPlacement.Leave, MapFraming.placementFor(null))
        assertEquals(
            "an aggregate over no rows answers nulls, not a rectangle at the origin",
            MapPlacement.Leave,
            MapFraming.placementFor(placed(null, null, null, null, count = 0)),
        )
        assertEquals(
            "and a count of zero with edges to spare is still nothing to frame",
            MapPlacement.Leave,
            MapFraming.placementFor(placed(0.0, 0.0, 0.0, 0.0, count = 0)),
        )
    }

    @Test
    fun positionsSpreadOverSeveralCitiesAreFittedAsARectangle() {
        val framing = MapFraming.placementFor(placed(35.68, 52.52, 13.40, 139.69))

        val fit = requireNotNull(framing as? MapPlacement.Fit)
        assertEquals(
            "min stays min: north, east, south, west is the order the widget takes, and swapping a latitude " +
                "for a longitude puts Berlin in the Atlantic",
            MapBounds(minLatitude = 35.68, maxLatitude = 52.52, minLongitude = 13.40, maxLongitude = 139.69),
            fit.bounds,
        )
    }

    @Test
    fun onePhotoOpensOnItRatherThanOnABoxWithNoArea() {
        val framing = MapFraming.placementFor(placed(52.5, 52.5, 13.4, 13.4, count = 1))

        val centre = requireNotNull(framing as? MapPlacement.Centre)
        assertEquals(MediaLocation(52.5, 13.4), centre.at)
        assertEquals(MapFraming.SINGLE_PLACE_ZOOM, centre.zoom, 0.0)
    }

    @Test
    fun aThousandPhotosTakenInOneRoomAreOnePlaceToo() {
        // A bounding box a few metres wide cannot be *fitted*: the zoom that would fit it is deeper than any
        // provider ships, which is how a map ends up showing one tile of a doorway at the sky.
        val framing = MapFraming.placementFor(
            placed(35.6800, 35.6804, 139.6900, 139.6906, count = 1_000),
        )

        val centre = requireNotNull(framing as? MapPlacement.Centre)
        assertEquals(35.6802, centre.at.latitude, 0.0001)
        assertEquals(139.6903, centre.at.longitude, 0.0001)
    }

    @Test
    fun aRectangleThatDoesNotExistOnEarthFramesNothing() {
        assertEquals(
            "a stored latitude of 120 has no north edge",
            MapPlacement.Leave,
            MapFraming.placementFor(placed(120.0, 121.0, 13.0, 14.0)),
        )
        assertEquals(
            "and one that spans past the antimeridian is not a place either",
            MapPlacement.Leave,
            MapFraming.placementFor(placed(10.0, 20.0, -200.0, 30.0)),
        )
        assertEquals(
            "nor an inverted pair, which no MIN/MAX query can produce and every hand-built box can",
            MapPlacement.Leave,
            MapFraming.placementFor(placed(52.0, 35.0, 13.0, 14.0)),
        )
    }

    @Test
    fun aMapThatHasBeenPlacedOnceIsLeftAloneFromThenOn() {
        val fit = MapPlacement.Fit(MapBounds(35.0, 52.0, 13.0, 139.0))

        assertTrue(MapFraming.shouldPlace(alreadyPlaced = false, placement = fit))
        assertFalse(
            "the second request is the one that fights the user's own pan",
            MapFraming.shouldPlace(alreadyPlaced = true, placement = fit),
        )
        assertFalse(
            "and there is nothing to place when the library has no positions in it",
            MapFraming.shouldPlace(alreadyPlaced = false, placement = MapPlacement.Leave),
        )
    }
}

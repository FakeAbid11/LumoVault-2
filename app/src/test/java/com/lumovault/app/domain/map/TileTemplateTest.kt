package com.lumovault.app.domain.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The tile URL, which is the map's only conversation with somebody else's server.
 *
 * A wrong template produces no error — osmdroid asks for a URL that 404s and the map renders as a blank
 * background under working markers, which is indistinguishable from a network problem to anyone who did not
 * write the build script. So the template is validated and substituted here, in code that can be asserted.
 */
class TileTemplateTest {
    @Test
    fun aTemplateThatNamesAllThreeCoordinatesCanAddressATile() {
        val template = TileTemplate.of("https://tiles.example.org/v1/{z}/{x}/{y}.png")

        assertEquals(
            "the {x} is replaced by the column, {y} by the row, and {z} by the level",
            "https://tiles.example.org/v1/12/3456/2102.png",
            requireNotNull(template).urlFor(zoom = 12, x = 3456, y = 2102),
        )
    }

    @Test
    fun aKeyInTheQueryIsPartOfTheAddress() {
        // Providers that require a key put it after the tile path, and a template that dropped it would
        // authenticate nothing.
        val template = requireNotNull(
            TileTemplate.of("https://tiles.example.org/{z}/{x}/{y}.png?key=abc123"),
        )

        assertEquals("https://tiles.example.org/3/4/5.png?key=abc123", template.urlFor(3, 4, 5))
    }

    @Test
    fun anIncompleteTemplateIsNoTemplate() {
        // Each of these would build a URL, which is precisely the problem: a silent wrong answer is worse than
        // a build that reports no provider.
        assertNull(TileTemplate.of("https://tiles.example.org/{z}/{x}.png"))
        assertNull(TileTemplate.of("https://tiles.example.org/tiles.png"))
        assertNull(TileTemplate.of(""))
        assertNull(TileTemplate.of("   "))
    }

    @Test
    fun theTileHostThisBuildShipsWithCanActuallyAddressTiles() {
        // The default lives in `app/build.gradle.kts` as `MAP_TILE_URL`, and a Kotlin test cannot read it
        // without going through BuildConfig — so this is the copy that may drift. It is kept because the
        // failure it guards is silent in both directions: a default that answers `null` here gives every
        // build a blank basemap and a notice nobody thinks to remove, and every test in this file stays green
        // while that happens.
        val shipped = TileTemplate.of("https://tile.openstreetmap.org/{z}/{x}/{y}.png")

        assertEquals(
            "the standard slippy-map address, with no reordering of the parts",
            "https://tile.openstreetmap.org/12/3/4.png",
            requireNotNull(shipped).urlFor(zoom = 12, x = 3, y = 4),
        )
    }

    @Test
    fun aTemplateHasToBeAnAddress() {
        // Not a policy lecture, a filter: the value comes from a build property, and `example/{z}/{x}/{y}`
        // would be asked for over cleartext with a path that has no host — which fails as a blank map.
        assertNull(TileTemplate.of("tiles.example.org/{z}/{x}/{y}.png"))
        assertEquals(
            "an explicit cleartext host is accepted, because a self-hosted tile server on a LAN is a real " +
                "thing people run and refusing it would refuse the use case",
            "http://tiles.local/1/0/0.png",
            TileTemplate.of("http://tiles.local/{z}/{x}/{y}.png")?.urlFor(1, 0, 0),
        )
    }

    @Test
    fun aValueThatCouldBreakTheGeneratedSourceIsRejected() {
        // BuildConfig writes these out as Java string literals, so a quote, a backslash or a dollar sign in a
        // build property would otherwise turn a configuration mistake into an uncompilable build.
        assertNull(TileTemplate.of("https://tiles.example.org/\"{z}/{x}/{y}.png"))
        assertNull(TileTemplate.of("https://tiles.example.org/{z}/{x}/{y}.png\$foo"))
    }

    @Test
    fun surroundingWhitespaceIsNotPartOfTheAddress() {
        val template = TileTemplate.of("  https://tiles.example.org/{z}/{x}/{y}.png  ")

        assertEquals("https://tiles.example.org/1/2/3.png", requireNotNull(template).urlFor(1, 2, 3))
    }
}

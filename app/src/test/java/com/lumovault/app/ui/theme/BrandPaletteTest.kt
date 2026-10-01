package com.lumovault.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The brand palette pinned the way `ThemePaletteTest` pins the scheme: only what can be arithmetic.
 *
 * The design spec names three colours by literal — brandIndigo, brandSky and the `syncing` role it
 * says is equal to the sky — and says the seed is the one colour the whole Material scheme is built
 * from. Both halves are checkable: the literals must not drift, and every hue-bearing role of the
 * primary and secondary families must sit on the seed's hue rather than near it, because a scheme
 * re-hued "roughly" is a scheme that is quietly two blues again a year from now.
 *
 * Contrast and ramp ordering are not restated here; [ThemePaletteTest] owns those floors for the
 * whole scheme, and this file assumes it runs beside them.
 */
class BrandPaletteTest {

    @Test
    fun theSpecLiteralsAreTheOnesInTheScheme() {
        assertEquals(Color(0xFF2B5CE6), BrandIndigo)
        assertEquals(Color(0xFF4FA8FF), BrandSky)
        assertEquals(BrandSky, SyncingAccent)
        // The seed is not decoration beside the scheme: it *is* the light scheme's primary.
        assertEquals(BrandIndigo, LightPrimary)
    }

    @Test
    fun everyPrimaryAndSecondaryRoleCarriesTheSeedsHue() {
        val seed = hueOf(BrandIndigo)
        val family = listOf(
            DarkPrimary to "DarkPrimary",
            LightPrimary to "LightPrimary",
            DarkPrimaryContainer to "DarkPrimaryContainer",
            LightPrimaryContainer to "LightPrimaryContainer",
            DarkSecondaryContainer to "DarkSecondaryContainer",
            LightSecondaryContainer to "LightSecondaryContainer",
        )
        for ((color, name) in family) {
            val delta = hueDistance(hueOf(color), seed)
            assertTrue("$name sits ${delta} degrees off the seed's hue", delta <= HueWindowDegrees)
        }
    }

    /** The seed's hue in degrees, the same HSL conversion the scheme's derivation used. */
    private fun hueOf(color: Color): Float {
        val red = color.red
        val green = color.green
        val blue = color.blue
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            red -> 60f * (((green - blue) / delta) % 6f)
            green -> 60f * (((blue - red) / delta) + 2f)
            else -> 60f * (((red - green) / delta) + 4f)
        }
        return if (hue < 0f) hue + 360f else hue
    }

    /** The shorter arc between two hues: 359 and 1 are 2 degrees apart, not 358. */
    private fun hueDistance(a: Float, b: Float): Float {
        val raw = abs(a - b)
        return minOf(raw, 360f - raw)
    }

    private companion object {
        /** Far enough that a future tone adjustment is not a red build, near enough that "the same
         * blue" still means it: two blues a screen apart drift past this. */
        const val HueWindowDegrees = 15f
    }
}

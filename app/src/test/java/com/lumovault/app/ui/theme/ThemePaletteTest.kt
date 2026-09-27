package com.lumovault.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette, checked as a system rather than admired as a screenshot.
 *
 * Two things are objectively wrong in a colour set, and both have happened here: tiers that do not order
 * (so "a raised panel" means nothing and two surfaces of the same tone sit side by side), and text that
 * fails contrast (which is not a taste problem at all). Everything else about how the app looks is a human
 * judgement and is left to the screen — this file asserts only what can be arithmetic.
 *
 * The thresholds are WCAG's: 4.5:1 for body text, 3:1 for a control or an icon that carries meaning. The
 * divider colour is deliberately not asserted: a hairline is decoration, and forcing it to 3:1 would make
 * it a border.
 */
class ThemePaletteTest {

    @Test
    fun theDarkRampRisesAndTheLightRampFalls() {
        val darkRising = listOf(
            DarkSurfaceContainerLowest,
            DarkSurfaceContainerLow,
            DarkSurfaceContainer,
            DarkSurfaceContainerHigh,
            DarkSurfaceContainerHighest,
        )
        assertStrictlyAscending(darkRising, "dark container ramp")
        assertTrue("dark `dim` must recess below the surface", DarkSurfaceDim.luminance() < DarkSurface.luminance())
        assertTrue(
            "dark `bright` must lift above it",
            DarkSurfaceBright.luminance() > DarkSurfaceContainerHighest.luminance(),
        )

        val lightFalling = listOf(
            LightSurfaceContainerLowest,
            LightSurfaceContainerLow,
            LightSurfaceContainer,
            LightSurfaceContainerHigh,
            LightSurfaceContainerHighest,
        )
        assertStrictlyDescending(lightFalling, "light container ramp")
        assertTrue("light `dim` must recess below the surface", LightSurfaceDim.luminance() < LightSurface.luminance())
        assertTrue(
            "light `bright` must lift above it",
            LightSurfaceBright.luminance() > LightSurface.luminance(),
        )
    }

    @Test
    fun theDarkTiersCarryTheExistingSurfaceAndItsVariant() {
        // A screen already drawn against `surface` or `surfaceVariant` must not jump tone when the ramp
        // lands: the two values have to sit on the ladder rather than beside it.
        val ladder = listOf(
            DarkSurfaceContainerLowest,
            DarkSurfaceContainerLow,
            DarkSurfaceContainer,
            DarkSurfaceContainerHigh,
            DarkSurfaceContainerHighest,
        )
        assertTrue(
            "surface belongs between the low and the middle tier",
            DarkSurface.luminance() > DarkSurfaceContainerLow.luminance() &&
                DarkSurface.luminance() < DarkSurfaceContainer.luminance(),
        )
        assertTrue(
            "surfaceVariant is the high tier, so a chip and a raised card agree",
            ladder.any { it.luminance() == DarkSurfaceVariant.luminance() },
        )
        assertTrue(
            "and the light scheme makes the same pair agree",
            LightSurfaceVariant.luminance() == LightSurfaceContainerHighest.luminance(),
        )
    }

    @Test
    fun bodyTextClearsFourFiveOneOnEverySurfaceInBothSchemes() {
        for (surface in DarkSurfaces) {
            assertAtLeast(4.5f, contrast(DarkOnSurface, surface), "onSurface on ${Hex.of(surface)}")
            assertAtLeast(4.5f, contrast(DarkOnSurfaceVariant, surface), "onSurfaceVariant on ${Hex.of(surface)}")
        }
        for (surface in LightSurfaces) {
            assertAtLeast(4.5f, contrast(LightOnSurface, surface), "onSurface on ${Hex.of(surface)}")
            assertAtLeast(4.5f, contrast(LightOnSurfaceVariant, surface), "onSurfaceVariant on ${Hex.of(surface)}")
        }
    }

    @Test
    fun controlsAndIconsClearThreeOnEverySurface() {
        for (surface in DarkSurfaces) {
            assertAtLeast(3.0f, contrast(DarkPrimary, surface), "primary on ${Hex.of(surface)}")
            assertAtLeast(3.0f, contrast(DarkOutline, surface), "outlined icon on ${Hex.of(surface)}")
            assertAtLeast(3.0f, contrast(DarkError, surface), "error statement on ${Hex.of(surface)}")
        }
        for (surface in LightSurfaces) {
            assertAtLeast(3.0f, contrast(LightPrimary, surface), "primary on ${Hex.of(surface)}")
            assertAtLeast(3.0f, contrast(LightOutline, surface), "outlined icon on ${Hex.of(surface)}")
            assertAtLeast(3.0f, contrast(ErrorRed, surface), "error statement on ${Hex.of(surface)}")
        }
    }

    @Test
    fun aMessageAndItsContainerStayReadableTogether() {
        assertAtLeast(4.5f, contrast(DarkOnErrorContainer, DarkErrorContainer), "error container text")
        assertAtLeast(4.5f, contrast(LightOnErrorContainer, LightErrorContainer), "light error container text")
        assertAtLeast(4.5f, contrast(DarkOnPrimaryContainer, DarkPrimaryContainer), "primary container text")
        assertAtLeast(4.5f, contrast(LightOnPrimaryContainer, LightPrimaryContainer), "light primary container text")
        assertAtLeast(4.5f, contrast(DarkOnPrimary, DarkPrimary), "button label")
        assertAtLeast(4.5f, contrast(LightOnPrimary, LightPrimary), "light button label")
        // The floating chip in the date rail: white-ish text on the inverted surface, in both schemes.
        assertAtLeast(4.5f, contrast(DarkOnInverseSurface, DarkInverseSurface), "inverse chip text")
        assertAtLeast(4.5f, contrast(LightOnInverseSurface, LightInverseSurface), "light inverse chip text")
    }

    @Test
    fun aWhiteGlyphSurvivesTheLightestPhotographBehindIt() {
        // Badges and glyphs sit on the user's own picture, so the scheme cannot supply their colours. The
        // honest test is the worst case: a white sky. Composite the scrim over white, then ask whether the
        // white mark on it is still a control and not a smudge.
        assertAtLeast(3.0f, contrast(OnMedia, MediaGlyphScrim.overWhite()), "glyph on the glyph scrim")
        assertAtLeast(3.0f, contrast(OnMedia, MediaBadgeScrim.overWhite()), "glyph on the badge scrim")
        assertAtLeast(3.0f, contrast(OnMedia, ChromeScrim.overWhite()), "viewer control on the chrome band")
    }

    private val DarkSurfaces = listOf(
        DarkBackground,
        DarkSurface,
        DarkSurfaceDim,
        DarkSurfaceBright,
        DarkSurfaceVariant,
        DarkSurfaceContainerLowest,
        DarkSurfaceContainerLow,
        DarkSurfaceContainer,
        DarkSurfaceContainerHigh,
        DarkSurfaceContainerHighest,
    )

    private val LightSurfaces = listOf(
        LightBackground,
        LightSurface,
        LightSurfaceDim,
        LightSurfaceBright,
        LightSurfaceVariant,
        LightSurfaceContainerLowest,
        LightSurfaceContainerLow,
        LightSurfaceContainer,
        LightSurfaceContainerHigh,
        LightSurfaceContainerHighest,
    )

    private fun assertStrictlyAscending(colors: List<Color>, label: String) =
        assertOrdered(colors, label) { a, b -> a < b }

    private fun assertStrictlyDescending(colors: List<Color>, label: String) =
        assertOrdered(colors, label) { a, b -> a > b }

    private fun assertOrdered(colors: List<Color>, label: String, cmp: (Float, Float) -> Boolean) {
        colors.zipWithNext().forEachIndexed { index, (before, after) ->
            assertTrue(
                "$label: step ${index + 1} to ${index + 2} must be strictly ordered (${Hex.of(before)}, ${Hex.of(after)})",
                cmp(before.luminance(), after.luminance()),
            )
        }
    }

    private fun assertAtLeast(minimum: Float, actual: Float, what: String) {
        assertTrue(
            "$what is $actual:1, below the $minimum:1 it exists to clear",
            actual >= minimum,
        )
    }

    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }

    /** A translucent colour as it actually lands: composited over the brightest photo it can meet. */
    private fun Color.overWhite(): Color = Color(
        red = red * alpha + (1f - alpha),
        green = green * alpha + (1f - alpha),
        blue = blue * alpha + (1f - alpha),
    )

    /** Failure messages need to say which colour, and a Float triple does not. */
    private object Hex {
        fun of(color: Color): String = buildString {
            append('#')
            append(color.red.toByteChannel())
            append(color.green.toByteChannel())
            append(color.blue.toByteChannel())
        }

        private fun Float.toByteChannel(): String = ((this * 255f) + 0.5f).toInt().toString(16).padStart(2, '0')
    }
}

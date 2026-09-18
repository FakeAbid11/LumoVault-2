package com.lumovault.lumovault.core.theme

import androidx.compose.ui.graphics.Color

/**
 * LumoVault's brand palette.
 *
 * Ported from lib/core/theme/app_colors.dart. [seed] is the only value the
 * Material scheme is built from — every surface, container and outline role is
 * derived by the color scheme generator, not hand-listed here, so they can
 * never drift apart. The remaining constants are for the handful of places that
 * need a literal brand colour outside the scheme (see [syncing]).
 */
object AppColors {

    /** The scheme seed: a saturated indigo. */
    val seed: Color = brandIndigo

    /** The primary brand indigo. */
    val brandIndigo = Color(0xFF2B5CE6)

    /** Lighter sky-blue, used for the in-flight transfer accent. */
    val brandSky = Color(0xFF4FA8FF)

    /**
     * In-flight transfer accent, for the upload/sync status indicators.
     *
     * Not a scheme role: these badges sit on the gallery's dark scrim and the
     * viewer's black backdrop, where `colorScheme.primary` would be either too
     * dark (light theme) or indistinguishable from the surrounding chrome. This
     * sky-blue reads clearly on black in both themes and signals "moving data".
     */
    val syncing: Color = brandSky
}

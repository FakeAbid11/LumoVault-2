package com.lumovault.app.ui.theme

import androidx.compose.ui.graphics.Color

// The original LumoVault theme is not a hand-picked palette: it is `ColorScheme.fromSeed` with
// `seed = 0xFF2B5CE6` and the *vibrant* variant, at contrast level 0, in light and dark
// (`lib/core/theme/app_theme.dart` of the Flutter app). Every value below is that scheme's output,
// transcribed rather than re-invented, because the two apps are meant to look like one product and a
// remembered "roughly that blue" is what made them diverge.
//
// The seed itself never appears in the app: `fromSeed` resolves it to `primary` 0xFFB6C4FF on the dark
// scheme and 0xFF004EE7 on the light one. Keeping the seed as a comment is the honest record of where
// the identity comes from — the blue/indigo family the PRD names — while the roles below are what is
// actually drawn.

// Dark scheme (the app's default appearance; PRD section 44).
internal val DarkPrimary = Color(0xFFB6C4FF)
internal val DarkOnPrimary = Color(0xFF00277F)
internal val DarkPrimaryContainer = Color(0xFF003AB1)
internal val DarkOnPrimaryContainer = Color(0xFFDCE1FF)
internal val DarkSecondary = Color(0xFFC7C2EA)
internal val DarkOnSecondary = Color(0xFF2F2D4C)
internal val DarkSecondaryContainer = Color(0xFF464364)
internal val DarkOnSecondaryContainer = Color(0xFFE4DFFF)
internal val DarkTertiary = Color(0xFFD3BDF5)
internal val DarkTertiaryContainer = Color(0xFF503E6D)
internal val DarkOnTertiaryContainer = Color(0xFFECDCFF)
internal val DarkBackground = Color(0xFF11131C)
internal val DarkSurface = Color(0xFF11131C)
internal val DarkSurfaceDim = Color(0xFF11131C)
internal val DarkSurfaceBright = Color(0xFF373943)
internal val DarkSurfaceContainerLowest = Color(0xFF0B0E17)
internal val DarkSurfaceContainerLow = Color(0xFF191B25)
internal val DarkSurfaceContainer = Color(0xFF1D1F29)
internal val DarkSurfaceContainerHigh = Color(0xFF272934)
internal val DarkSurfaceContainerHighest = Color(0xFF32343F)
internal val DarkOnSurface = Color(0xFFE1E1EF)
internal val DarkOnSurfaceVariant = Color(0xFFC4C5D6)
internal val DarkOutline = Color(0xFF8E909F)
internal val DarkOutlineVariant = Color(0xFF434654)
internal val DarkInverseSurface = Color(0xFFE1E1EF)
internal val DarkInverseOnSurface = Color(0xFF2E303A)

// Light scheme: the same seed, the same variant, the light tonal ramp.
internal val LightPrimary = Color(0xFF004EE7)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFDCE1FF)
internal val LightOnPrimaryContainer = Color(0xFF003AB1)
internal val LightSecondary = Color(0xFF5E5B7D)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFE4DFFF)
internal val LightOnSecondaryContainer = Color(0xFF464364)
internal val LightTertiary = Color(0xFF685587)
internal val LightTertiaryContainer = Color(0xFFECDCFF)
internal val LightOnTertiaryContainer = Color(0xFF503E6D)
internal val LightBackground = Color(0xFFFAF8FF)
internal val LightSurface = Color(0xFFFAF8FF)
internal val LightSurfaceDim = Color(0xFFD9D9E7)
internal val LightSurfaceBright = Color(0xFFFAF8FF)
internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceContainerLow = Color(0xFFF3F2FF)
internal val LightSurfaceContainer = Color(0xFFEDEDFB)
internal val LightSurfaceContainerHigh = Color(0xFFE7E7F5)
internal val LightSurfaceContainerHighest = Color(0xFFE1E1EF)
internal val LightOnSurface = Color(0xFF191B25)
internal val LightOnSurfaceVariant = Color(0xFF434654)
internal val LightOutline = Color(0xFF747685)
internal val LightOutlineVariant = Color(0xFFC4C5D6)
internal val LightInverseSurface = Color(0xFF2E303A)
internal val LightInverseOnSurface = Color(0xFFEFF0FE)

// Drawn on top of a photograph, so these cannot come from the colour scheme: a surface colour chosen for a
// dark or light app background is the wrong value for a badge over whatever the user's picture happens to be.
// The two scrim alphas are the Flutter app's own — `Colors.black54` behind a tile badge, `black38` to dim a
// still frame — kept out of the scheme so a theme change cannot mute them.
internal val MediaBadgeScrim = Color(0x8A000000)
internal val MediaStillFrameDim = Color(0x61000000)

/** A mark on a photograph needs a harder backing than a badge: this one carries a whole glyph. */
internal val MediaGlyphScrim = Color(0x99000000)
internal val OnMedia = Color(0xFFFFFFFF)

/** The viewer's chrome band, and the map's notices — both float over content the app does not control. */
internal val ChromeScrim = Color(0xA6000000)
internal val FullScreenScrim = Color(0xCC000000)
internal val MapNoticeScrim = Color(0x99000000)

/**
 * The two states that are not errors and not the accent.
 *
 * The Flutter app names these as literals rather than deriving them (`status_color.dart`): a queue item
 * waiting is amber and one that succeeded is green whatever the scheme says, because those two colours carry
 * their meaning in every app the user has ever used and a seed-derived replacement would be a puzzle.
 */
internal val PendingAmber = Color(0xFFFFA726)
internal val SuccessGreen = Color(0xFF4CAF50)

/** Not the theme's primary: a heart in the accent colour would compete with the selection ring for
 * "this one is special". */
internal val FavoriteAccent = Color(0xFFFF5A6E)
internal val BackedUpAccent = Color(0xFF7DE3A0)

internal val ErrorRed = Color(0xFFBA1A1A)
internal val OnErrorRed = Color(0xFFFFFFFF)
internal val DarkErrorRed = Color(0xFFFFB4AB)

internal val LightErrorContainer = Color(0xFFFFDAD6)
internal val LightOnErrorContainer = Color(0xFF93000A)
internal val DarkErrorContainer = Color(0xFF93000A)
internal val DarkOnErrorContainer = Color(0xFFFFDAD6)

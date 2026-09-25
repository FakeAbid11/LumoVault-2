package com.lumovault.app.ui.theme

import androidx.compose.ui.graphics.Color

// Dark scheme: black + dark blue surfaces, blue accents (PRD section 44).
internal val DarkBackground = Color(0xFF05070D)
internal val DarkSurface = Color(0xFF0C1220)
internal val DarkSurfaceVariant = Color(0xFF16203A)
internal val DarkOnSurface = Color(0xFFE6EDF8)
internal val DarkOnSurfaceVariant = Color(0xFFA9B7CE)
internal val DarkPrimary = Color(0xFF4C8DFF)
internal val DarkOnPrimary = Color(0xFF001429)
internal val DarkPrimaryContainer = Color(0xFF1B3A67)
internal val DarkOnPrimaryContainer = Color(0xFFCFE0FF)
internal val DarkOutline = Color(0xFF43526B)

// Light scheme: white + light blue surfaces, the same blue accent family.
internal val LightBackground = Color(0xFFFFFFFF)
internal val LightSurface = Color(0xFFF4F8FF)
internal val LightSurfaceVariant = Color(0xFFDCE9FA)
internal val LightOnSurface = Color(0xFF0C1420)
internal val LightOnSurfaceVariant = Color(0xFF43526B)
internal val LightPrimary = Color(0xFF0F5FD8)
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD3E3FF)
internal val LightOnPrimaryContainer = Color(0xFF00296B)
internal val LightOutline = Color(0xFF7E8CA3)

// Drawn on top of a photograph, so these cannot come from the colour scheme: a surface colour chosen for a
// dark or light app background is the wrong value for a badge over whatever the user's picture happens to be.
// They live here rather than in each screen because four screens need them and two had already drifted apart.
internal val MediaBadgeScrim = Color(0xB3000000)
internal val OnMedia = Color(0xFFFFFFFF)
internal val FullScreenScrim = Color(0xCC000000)
internal val MapNoticeScrim = Color(0x99000000)

/** The two states of a thumbnail's own glyph, kept out of the scheme so a theme change cannot mute them. */
/** Not the theme's primary: a heart in the accent colour would compete with the selection ring for
 * "this one is special". */
internal val FavoriteAccent = Color(0xFFFF5A6E)
internal val BackedUpAccent = Color(0xFF7DE3A0)

internal val ErrorRed = Color(0xFFB3261E)
internal val OnErrorRed = Color(0xFFFFFFFF)

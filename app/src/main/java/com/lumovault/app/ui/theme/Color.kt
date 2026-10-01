package com.lumovault.app.ui.theme

import androidx.compose.ui.graphics.Color

/*
 * The brand palette, named for the design spec (app_colors.dart). brandIndigo is the seed the whole
 * Material scheme is built from: every hue-bearing role below carries the seed's hue (224.3 degrees,
 * rotated from the blues the scheme shipped with), while each role's tone stays where it was — the
 * tones are what `ThemePaletteTest` encodes as ramp ordering and WCAG floors, and a re-hue must not
 * quietly renegotiate those. brandSky is the spec's own literal and keeps the hue the spec drew it
 * at (a lighter, slightly cyan step off the seed); only the scheme's roles are rotated. The error
 * family, the scrims and the thumbnail accents are out of the scheme by design and are never rotated.
 */
internal val BrandIndigo = Color(0xFF2B5CE6)

/** The spec's lighter sky-blue: the step between the seed and white, available to UI that needs
 * the brand without the seed's weight. */
internal val BrandSky = Color(0xFF4FA8FF)

/** The spec's `syncing` role, equal to [BrandSky] by the spec's own note. In-flight upload and
 * transfer marks only — a queue ring, a restore bar, a viewer's load spinner — never generic busy
 * work, and never a mark drawn over a photograph: at 0x8C over a white sky it lands near 1.9:1,
 * below the floor `ThemePaletteTest` holds white glyphs to, which is why badges stay white. */
internal val SyncingAccent = BrandSky

// Dark scheme: black + dark blue surfaces, blue accents (PRD section 44).
internal val DarkBackground = Color(0xFF05070D)
internal val DarkSurface = Color(0xFF0C1120)
internal val DarkSurfaceVariant = Color(0xFF161F3A)
internal val DarkOnSurface = Color(0xFFE6EBF8)
internal val DarkOnSurfaceVariant = Color(0xFFA9B3CE)
internal val DarkPrimary = Color(0xFF4C7BFF)
internal val DarkOnPrimary = Color(0xFF000B29)
internal val DarkPrimaryContainer = Color(0xFF1B2F67)
internal val DarkOnPrimaryContainer = Color(0xFFCFDCFF)
internal val DarkOutline = Color(0xFF6E7B9E)

// Light scheme: white + light blue surfaces, the same blue accent family. The primary is the seed
// itself: the brand colour should be what a light-scheme button wears.
internal val LightBackground = Color(0xFFFFFFFF)
internal val LightSurface = Color(0xFFF4F7FF)
internal val LightSurfaceVariant = Color(0xFFDCE4FA)
internal val LightOnSurface = Color(0xFF0C1120)
internal val LightOnSurfaceVariant = Color(0xFF434D6B)
internal val LightPrimary = BrandIndigo
internal val LightOnPrimary = Color(0xFFFFFFFF)
internal val LightPrimaryContainer = Color(0xFFD3DFFF)
internal val LightOnPrimaryContainer = Color(0xFF001C6B)
internal val LightOutline = Color(0xFF66708C)

/*
 * The raised-surface ramp, in both schemes.
 *
 * Five screens already draw panels with `surfaceContainerHigh` — the map's pin card, the album cover's
 * badge, the album detail and timeline bars — while `Theme.kt` never defined the container tiers, so
 * Material 3 answered with its baseline *neutral grey*. That is the one thing a photo library cannot
 * afford: a grey panel sitting beside a navy one reads as two apps, and it is most visible exactly where
 * the eye rests, on the cards over the pictures.
 *
 * These are the same hue family as `surface`, stepped in tone rather than shifted in colour, and ordered
 * so a higher tier is always the more raised one — `ThemePaletteTest` asserts that ordering and the
 * contrast of text on each step, because a ramp nobody can see the difference in is decoration, not a
 * system. `dim` recesses and `bright` lifts against `surface` in both schemes, which is what a bottom bar
 * and a dialog respectively need.
 */
internal val DarkSurfaceContainerLowest = Color(0xFF010307)
internal val DarkSurfaceDim = Color(0xFF060912)
internal val DarkSurfaceContainerLow = Color(0xFF0B1120)
internal val DarkSurfaceContainer = Color(0xFF101628)
internal val DarkSurfaceContainerHigh = Color(0xFF161F3A)
internal val DarkSurfaceContainerHighest = Color(0xFF1D294A)
internal val DarkSurfaceBright = Color(0xFF242F4F)

/** Hairlines and dividers: quieter than [DarkOutline], which is the icon colour and has to stay at 3:1. */
internal val DarkOutlineVariant = Color(0xFF3E4968)

internal val LightSurfaceContainerLowest = Color(0xFFFFFFFF)
internal val LightSurfaceBright = Color(0xFFFBFCFF)
internal val LightSurfaceContainerLow = Color(0xFFF8FAFF)
internal val LightSurfaceContainer = Color(0xFFEFF3FD)
internal val LightSurfaceContainerHigh = Color(0xFFE6EBF9)
internal val LightSurfaceContainerHighest = Color(0xFFDCE4FA)
internal val LightSurfaceDim = Color(0xFFD3DBF1)
internal val LightOutlineVariant = Color(0xFFC3CBE0)

// Drawn on top of a photograph, so these cannot come from the colour scheme: a surface colour chosen for a
// dark or light app background is the wrong value for a badge over whatever the user's picture happens to be.
// They live here rather than in each screen because four screens need them and two had already drifted apart.
internal val MediaBadgeScrim = Color(0xB3000000)

/** Lighter than the pill's: a disc sits behind a whole glyph for the whole time the grid is on screen, while a
 * tag is read once and should be the quietest thing that is still legible. */
internal val MediaGlyphScrim = Color(0x8C000000)
internal val OnMedia = Color(0xFFFFFFFF)
internal val FullScreenScrim = Color(0xCC000000)

/**
 * The band behind the viewer's top bar, fading down into the photograph.
 *
 * The bar's own glyphs are white, and white is the colour a bright sky, a wedding dress and a snow scene all
 * contain — a control that disappears on exactly the photos that need it most. A *band* rather than a solid
 * bar because the photograph is the screen: the top of it may be darkened where the controls are, and the rest
 * may be left alone.
 */
internal val ChromeScrim = Color(0xA6000000)
internal val MapNoticeScrim = Color(0x99000000)

/** The two states of a thumbnail's own glyph, kept out of the scheme so a theme change cannot mute them. */
/** Not the theme's primary: a heart in the accent colour would compete with the selection ring for
 * "this one is special". */
internal val FavoriteAccent = Color(0xFFFF5A6E)
internal val BackedUpAccent = Color(0xFF7DE3A0)

internal val ErrorRed = Color(0xFFB3261E)
internal val OnErrorRed = Color(0xFFFFFFFF)
internal val LightErrorContainer = Color(0xFFF9DEDC)
internal val LightOnErrorContainer = Color(0xFF410E02)

/**
 * The error colours for the dark scheme, which is this app's default.
 *
 * A light-scheme red — the M3 baseline's own warning — sits at under 3:1 on these near-black surfaces,
 * so the one message type that must not be missed was the one hardest to read. The dark scheme therefore
 * inverts the role: a pale red statement on a deep-red container.
 */
internal val DarkError = Color(0xFFF2B8B5)
internal val DarkOnError = Color(0xFF681A14)
internal val DarkErrorContainer = Color(0xFF8C1D18)
internal val DarkOnErrorContainer = Color(0xFFF9DEDC)

/**
 * The floating-label chip (the date rail's month pill) in each scheme.
 *
 * Left unset, Material 3 answers with its baseline purple-grey — an off-brand slab that, in the dark
 * scheme, was almost white over the user's own photographs. These match the badge family the rest of
 * the media surfaces use: a dark blue-tinted chip with the scheme's own text colour on it.
 */
internal val DarkInverseSurface = Color(0xFF2A3040)
internal val DarkOnInverseSurface = Color(0xFFE6EBF8)
internal val LightInverseSurface = Color(0xFF253257)
internal val LightOnInverseSurface = Color(0xFFEDF1FB)

/*
 * The secondary roles, which were never defined at all.
 *
 * `FilledTonalButton` paints itself with `secondaryContainer`, and with that role unset Material 3 answered
 * with its baseline purple — the same class of miss as the surface tiers above, and visible in exactly one
 * place: the primary action of all three cards on the setup step, sitting as a desaturated mauve beside the
 * correct blue `Continue` pinned at the bottom of the same screen. On the `surfaceVariant` card those buttons
 * are meant to sit inside, the baseline purple is barely a step away in tone, so the app's most important tap
 * on the screen read as part of the panel behind it.
 *
 * A one-hue brand has nowhere obvious to put a second accent, so this is deliberately *not* an accent: it is a
 * neutral blue-grey, quieter than `primaryContainer` rather than different from it, because a card action that
 * matched the screen's own primary button would be two things shouting. Each container carries its scheme's
 * existing `onSurface` as its label colour — the pairing is then inherited from a text colour the palette test
 * already proves legible on every surface, instead of being a new value nobody checked.
 */
internal val DarkSecondary = Color(0xFFB7BFD6)
internal val DarkOnSecondary = Color(0xFF232C46)
internal val DarkSecondaryContainer = Color(0xFF3B4666)
internal val DarkOnSecondaryContainer = DarkOnSurface

internal val LightSecondary = Color(0xFF4A5B89)
internal val LightOnSecondary = Color(0xFFFFFFFF)
internal val LightSecondaryContainer = Color(0xFFB0BDE2)
internal val LightOnSecondaryContainer = LightOnSurface

/**
 * `tertiary` is left unset on purpose.
 *
 * Nothing in the app reads it, and a third accent family invented for completeness is a colour nobody will
 * defend in review. If a component starts using it, it gets the same treatment as these two: a value chosen
 * against the surface it has to separate from, and an assertion that says so.
 */

package com.lumovault.app.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Metrics shared by every screen that draws a thumbnail grid.
 *
 * Each one declared its own copy: the cell size and spacing were identical in three files and the badge values
 * in two, which is consistency only by accident — nothing stopped a fourth screen from inventing a fifth pair,
 * and the 18 dp / 26 dp badge icons show what happens once the copies do drift. These are the same numbers that
 * were already drawn, gathered in one place. One-offs that belong to a single surface, like the album cover's own
 * badge or the viewer's chrome, stay private to that file.
 *
 * `GridCellMinSize` is the one number here a user sees on every screen, so it is the one worth stating a reason
 * for: `GridCells.Adaptive` fits as many columns as it can at *at least* this width, and 110 dp fits two
 * columns on a 360 dp phone and three on a 411 dp one — a wall of very large thumbnails, which is the opposite
 * of a library you scan with your eye. At 96 dp the same devices give three and four columns, and because both
 * the timeline and the cloud grid ask for the same minimum, a photo is roughly the same size wherever it turns
 * up.
 */
internal val GridCellMinSize = 96.dp
internal val GridSpacing = 2.dp
internal val GroupCardCorner = 12.dp
internal val MediaThumbCorner = 4.dp
internal val MediaBadgeCorner = 4.dp
internal val MediaBadgeInset = 6.dp
internal val MediaBadgePadding = 4.dp

/** A mark on a photograph: a 16 dp glyph needs the dark disc behind it to survive a bright sky, so the disc,
 * not the glyph, sets the size — and 26 dp is still small against a 100 dp cell. */
internal val MediaGlyphSize = 26.dp
internal val MediaGlyphIconSize = 16.dp

/**
 * The timeline's date rail. Its width is also the padding the grid gives up to it, so the two cannot drift
 * apart and leave the rail sitting on top of the rightmost column of photos — the strip is reserved, not
 * overlaid.
 *
 * The overlay is the second, wider measure: a transparent layer that reaches left over the grid and carries the
 * month's name. It exists because text that is *measured* inside a 26 dp strip is text that gets cut off — the
 * strip is where the finger is, the overlay is where the words are, and only the strip takes the gesture, so
 * the overlay costs the grid nothing but a few pixels of a thumbnail while a drag is in progress.
 */
internal val RailWidth = 26.dp
internal val RailOverlayWidth = 104.dp
internal val RailTickWidth = 3.dp
internal val RailTickHeight = 12.dp
internal val RailTickActiveHeight = 24.dp
internal val RailTickEndInset = 8.dp

/**
 * The four- and eight-point scale every screen pads with, so "a bit of space" is one value rather than a
 * number each file happened to reach for. `ScreenEdge` is the one gutters and headers align to, which is what
 * makes a title sit over the first column of photos instead of a few pixels left of it.
 */
internal val SpaceXs = 4.dp
internal val SpaceSm = 8.dp
internal val SpaceMd = 12.dp
internal val SpaceLg = 16.dp
internal val SpaceXl = 24.dp
internal val ScreenEdge = 12.dp

/** Touch targets, because a control under a thumb has to be found before it can be pressed. */
internal val MinTouchTarget = 44.dp
internal val IconButtonSize = 40.dp

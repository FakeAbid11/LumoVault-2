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
 */
internal val GridCellMinSize = 110.dp
internal val GridSpacing = 2.dp
internal val GroupCardCorner = 8.dp
internal val MediaThumbCorner = 4.dp
internal val MediaBadgeCorner = 3.dp
internal val MediaBadgeInset = 6.dp
internal val MediaBadgePadding = 4.dp
internal val MediaBadgeIconSize = 18.dp

/**
 * The timeline's date rail. Its width is also the padding the grid gives up to it, so the two cannot drift
 * apart and leave the rail sitting on top of the rightmost column of photos — the strip is reserved, not
 * overlaid.
 */
internal val RailWidth = 26.dp
internal val RailTickWidth = 2.dp

/** The shortest slice of track a month label may occupy; below this, labels are thinned rather than stacked. */
internal val RailLabelMinHeight = 22.dp

package com.lumovault.app.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The measures every screen shares, taken from the reference app rather than chosen here.
 *
 * The Flutter codebase has no spacing or shape token file — every value is an inline literal, which is fine
 * in one codebase and exactly the failure mode in two: the native app had a day header at 16 sp regular on
 * one screen and 15 sp medium on another, a 4 dp thumbnail corner where the reference draws 12, and three
 * different badge paddings. These are the reference's numbers, gathered where a fourth screen cannot drift
 * from them.
 *
 * **Grid.** The reference timeline is edge-to-edge with a 2 dp gutter and *fixed* columns — 5 on a small
 * phone, 4 on a medium one, 3 on a large — rather than `Adaptive`, which is what let the native grid decide
 * that a 411 dp phone should have three enormous cells. [GridColumnsSmall]/[GridColumnsMedium]/
 * [GridColumnsLarge] are that rule; [GridCellMinSize] survives only as the fallback for a window too narrow
 * to count.
 *
 * **Corner radii** are the reference's, and they are not all the same value: a photo tile is 12, a chip on
 * top of one is 8, an album cover is 16, a card is 24, and the navigation capsule, dialogs and sheets are 28.
 */
internal val GridSpacing = 2.dp
internal val GridCellMinSize = 96.dp
internal val GridColumnsSmall = 5
internal val GridColumnsMedium = 4
internal val GridColumnsLarge = 3

internal val MediaThumbCorner = 12.dp
internal val MediaBadgeCorner = 8.dp
internal val MediaBadgeInset = 6.dp
internal val MediaBadgePadding = 6.dp
internal val MediaBadgePaddingVertical = 3.dp

/** A mark on a photograph: the disc, not the glyph, sets the size — 26 dp is still small on a 90 dp cell. */
internal val MediaGlyphSize = 26.dp
internal val MediaGlyphIconSize = 16.dp

/** A group card, a notice, the selection strip: the reference's input/chip radius. */
internal val GroupCardCorner = 16.dp
internal val CardCorner = 24.dp
internal val SheetCorner = 28.dp
internal val NavCapsuleCorner = 28.dp

/**
 * The scrubber, per `fast_scroll_scrubber.dart`: a 44 dp hit strip inset 6 from the right edge and 40 from
 * the top and bottom, a 48 × 6 handle that thickens to 8 while it is dragged, and a bubble 56 dp to the left
 * of it that fades 800 ms after the finger lifts.
 *
 * There are no tick marks and no month index in the reference — one handle whose bubble names the day the
 * list is looking at. The native app had drawn a month-per-tick rail, which is a different instrument, and it
 * is replaced rather than kept alongside.
 */
internal val ScrubberStripWidth = 44.dp
internal val ScrubberEndInset = 6.dp
internal val ScrubberVerticalInset = 40.dp
internal val ScrubberHandleWidth = 6.dp
internal val ScrubberHandleActiveWidth = 8.dp
internal val ScrubberHandleHeight = 48.dp
internal val ScrubberHandleCorner = 3.dp
internal val ScrubberBubbleCorner = 20.dp
internal val ScrubberBubbleGap = 56.dp
internal const val SCRUB_BUBBLE_LINGER_MILLIS = 800L
internal const val SCRUB_HANDLE_IDLE_ALPHA = 0.35f

/**
 * The four- and eight-point scale every screen pads with.
 *
 * [ScreenEdge] is 16 — the reference's app-bar, day-header and settings padding — and the photo grid itself
 * ignores it, running to the glass on both sides. That difference is deliberate: a timeline of pictures is
 * the one place in the app where content, not chrome, touches the edge.
 */
internal val SpaceXs = 4.dp
internal val SpaceSm = 8.dp
internal val SpaceMd = 12.dp
internal val SpaceLg = 16.dp
internal val SpaceXl = 24.dp
internal val ScreenEdge = 16.dp

/** The day header's own rhythm: 8 above and below a 24-line title, 16 in from the glass. */
internal val DayHeaderVertical = 8.dp
internal val DayHeaderHorizontal = 16.dp

/** Touch targets, because a control under a thumb has to be found before it can be pressed. */
internal val MinTouchTarget = 44.dp
internal val IconButtonSize = 40.dp
internal val NavHeight = 64.dp
internal val NavMargin = 16.dp
internal val NavIconSize = 22.dp

/**
 * How much clearance a scrolling list leaves at its foot.
 *
 * The reference shell floats its navigation capsule over the content and extends the body beneath it, so a
 * list that did not stop 96 dp early would end with somebody's photograph under the bar. That is the price of
 * the capsule, and paying it in one token is what keeps six screens from each guessing.
 */
internal val NavClearance: Dp = 96.dp

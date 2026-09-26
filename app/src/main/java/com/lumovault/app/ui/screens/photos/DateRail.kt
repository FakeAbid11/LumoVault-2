package com.lumovault.app.ui.screens.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.RailMonth
import com.lumovault.app.domain.model.TimelineRail
import com.lumovault.app.ui.theme.RailOverlayWidth
import com.lumovault.app.ui.theme.RailTickActiveHeight
import com.lumovault.app.ui.theme.RailTickEndInset
import com.lumovault.app.ui.theme.RailTickHeight
import com.lumovault.app.ui.theme.RailTickWidth
import com.lumovault.app.ui.theme.RailWidth
import com.lumovault.app.util.formatMonth
import java.time.YearMonth
import kotlinx.coroutines.delay

/**
 * The date rail: a month-per-tick strip down the right edge of the timeline.
 *
 * It is a *scrubber*, not a second list. It owns no scroll position and renders no photos: it turns a
 * finger's height into a grid index and hands that to the `LazyGridState` that was already there. That is the
 * only way it can serve ten years of photos and stay cheap — one tick per month of the loaded window, and
 * that window is already in memory for the grid itself, so the rail adds no query, no list and no copy.
 *
 * Two measures, and the split between them is the whole design. The **strip** is [RailWidth] wide, reserved by
 * the grid beside the last column of photos, and it is the only thing that takes a gesture. The **overlay** is
 * [RailOverlayWidth] wide, transparent, reaches over the grid and owns nothing you can touch: it is where the
 * month's name is drawn. That separation exists because of how the first version failed — the label was a
 * child of the 26 dp strip, so it was *measured* at 26 dp, and "Sep 2024" is neither 26 dp wide nor cut in half
 * politely. Measured inside the overlay, the same text ends where it should and reaches left across the photos,
 * which costs a few pixels of a thumbnail only while a name is actually being shown.
 *
 * The name appears for two questions. While the finger drags, it names the month under the finger — that is the
 * moment the user is asking. And for a moment after an ordinary scroll settles on a new month, it names the
 * month the grid is looking at, in the same place, because a highlight on a track of eight hundred pixels is a
 * mark the eye cannot resolve into a date. At every other time the rail shows ticks only: a label floating
 * permanently over the right edge of the grid would sit on somebody's thumbnail to say what the day header two
 * rows up already says.
 *
 * @param months the rail's shape, from [TimelineRail.months]; nothing is drawn when it is empty.
 * @param activeMonth the month the grid is currently looking at.
 * @param onScrub called with a grid index as the finger moves; the caller scrolls the real grid.
 */
@Composable
fun DateRail(
    months: List<RailMonth>,
    activeMonth: RailMonth?,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (months.isEmpty()) return

    val density = LocalDensity.current
    var trackPx by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf<Scrubbing?>(null) }
    var announced by remember { mutableStateOf<RailMonth?>(null) }
    var seen by remember { mutableStateOf<RailMonth?>(null) }

    // The pill outlives the finger by about a second: a drag that stops on a month is a decision about that
    // month, and it should be readable after the hand leaves the screen.
    LaunchedEffect(scrubbing) {
        if (scrubbing == null) return@LaunchedEffect
        delay(PILL_LINGER_MILLIS)
        scrubbing = null
    }

    // The same courtesy to a scroll that has just settled on a new month. The two null tests are the point:
    // opening the screen would otherwise announce its own top month to a user who asked for nothing, and while
    // a finger is on the rail the drag is already answering that question.
    LaunchedEffect(activeMonth) {
        val previous = seen
        val current = activeMonth
        seen = current
        if (current == null || previous == null || previous == current || scrubbing != null) return@LaunchedEffect
        announced = current
        delay(PILL_LINGER_MILLIS)
        announced = null
    }

    val railDescription = stringResource(R.string.photos_date_rail)
    val pillHeightPx = with(density) { PillHeight.toPx() }

    Box(
        modifier = modifier
            .width(RailOverlayWidth)
            .fillMaxHeight()
            .onSizeChanged { size -> trackPx = size.height.toFloat() },
    ) {
        // A faint rule behind the ticks, so they read as one instrument rather than as stray marks at the edge
        // of the screen, and it sits at the tick centres because that is where the eye looks for them.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = RailTickEndInset + RailTickWidth / 2)
                .width(GuideWidth)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = GuideAlpha)),
        )

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(RailWidth)
                .fillMaxHeight()
                .semantics { contentDescription = railDescription }
                // A vertical drag, because that is what a rail is: one finger along a strip of months. Taking
                // the gesture also *claims* it, which matters for one reason — the grid under the rail would
                // scroll on the same movement, and a scrubber whose own drag scrolls the list it is scrubbing
                // moves the answer away from the question.
                .pointerInput(months, trackPx) {
                    // Nothing can be divided by a track that has not been measured yet, which is the first frame.
                    if (trackPx <= 0f) return@pointerInput
                    // Height to fraction, fraction to index: both steps are [TimelineRail]'s arithmetic, which
                    // the tests assert, so the only thing left in this file that can be wrong is the drawing.
                    fun scrubTo(yPx: Float) {
                        val fraction = (yPx / trackPx).coerceIn(0f, 1f)
                        val index = TimelineRail.itemIndexFor(months, fraction)
                        val month = TimelineRail.monthFor(months, index)
                        if (month != null) scrubbing = Scrubbing(month.yearMonth, yPx)
                        announced = null
                        onScrub(index)
                    }
                    detectVerticalDragGestures(
                        // The first touch jumps to the month it landed on rather than waiting for a movement the
                        // user may not intend; every change after it keeps pill and grid in step with the finger.
                        onDragStart = { position -> scrubTo(position.y) },
                        onDragEnd = { scrubbing = null },
                        onDragCancel = { scrubbing = null },
                    ) { change, _ ->
                        scrubTo(change.position.y)
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                // A slice of the track per month, weighted by the items that month holds: the rail's shape is
                // the library's shape, so a third of the way down is a third of the way through the photos.
                months.forEach { month ->
                    val active = month == activeMonth
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(month.itemCount.coerceAtLeast(1).toFloat()),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Box(
                            modifier = Modifier
                                .padding(end = RailTickEndInset)
                                .width(RailTickWidth)
                                .height(if (active) RailTickActiveHeight else RailTickHeight)
                                .background(
                                    if (active) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    RoundedCornerShape(RailTickWidth),
                                ),
                        )
                    }
                }
            }
        }

        val pill = pillFor(
            scrubbing = scrubbing,
            announced = announced,
            months = months,
            trackPx = trackPx,
            pillHeightPx = pillHeightPx,
        )
        if (pill != null) {
            Surface(
                shape = RoundedCornerShape(PillCorner),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = PillShadow,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = with(density) { pill.topPx.toDp() }, end = RailTickEndInset)
                    .widthIn(max = RailOverlayWidth),
            ) {
                Text(
                    text = monthLabel(pill.month),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(horizontal = PillPadding, vertical = PillPaddingVertical),
                )
            }
        }
    }
}

/** Where the finger is on the rail: the month to name, and how far down to name it. */
private class Scrubbing(val month: YearMonth, val yPx: Float)

/** The month to name and how far down to name it, in the track's own pixels. */
private class Pill(val month: YearMonth, val topPx: Float)

/**
 * A drag wins over a scroll announcement, and an announcement is placed at its month's own centre.
 *
 * Both are clamped into the track: a pill that hangs off the top of the timeline when the finger reaches the
 * first month has just moved away from the thing it is naming.
 */
private fun pillFor(
    scrubbing: Scrubbing?,
    announced: RailMonth?,
    months: List<RailMonth>,
    trackPx: Float,
    pillHeightPx: Float,
): Pill? {
    val scrub = scrubbing
    if (scrub != null) return Pill(month = scrub.month, topPx = pillTop(scrub.yPx, trackPx, pillHeightPx))
    if (announced == null || trackPx <= 0f) return null
    val centre = TimelineRail.fractionFor(months, announced) * trackPx
    return Pill(month = announced.yearMonth, topPx = pillTop(centre, trackPx, pillHeightPx))
}

/**
 * The top edge of a pill centred on [centrePx].
 *
 * The centring is what makes the pill useful: a name whose middle is at the finger reads as the month the
 * finger is on, while a name that merely starts there always names the one below it. The clamp is what makes
 * it survivable at the two ends of the track, where half a pill would otherwise sit outside the timeline.
 */
private fun pillTop(centrePx: Float, trackPx: Float, pillHeightPx: Float): Float {
    val maxTop = (trackPx - pillHeightPx).coerceAtLeast(0f)
    return (centrePx - pillHeightPx / 2f).coerceIn(0f, maxTop)
}

@Composable
private fun monthLabel(month: YearMonth): String =
    formatMonth(month, showYear = month.year != YearMonth.now().year)

/** How long the name stays up after the finger leaves, and after a scroll settles on a new month. */
private const val PILL_LINGER_MILLIS = 900L

/** Half-strength, because a rail line the photos compete with is a rail line drawn twice too loudly. */
private const val GuideAlpha = 0.45f

private val PillHeight: Dp = 34.dp
private val PillCorner: Dp = 10.dp
private val PillShadow: Dp = 6.dp
private val PillPadding: Dp = 10.dp
private val PillPaddingVertical: Dp = 6.dp
private val GuideWidth: Dp = 1.dp

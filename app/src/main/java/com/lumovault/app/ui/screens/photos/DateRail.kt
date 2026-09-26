package com.lumovault.app.ui.screens.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.RailMonth
import com.lumovault.app.domain.model.TimelineRail
import com.lumovault.app.ui.theme.RailLabelMinHeight
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
 * Two directions are drawn, and the difference between them is the design. While the finger is down the pill
 * names the month under it, because that is the moment the user is asking a question. At every other time the
 * rail only marks where the grid is looking — the highlighted tick — because a label floating permanently over
 * the right edge of the grid would sit on top of somebody's thumbnail to say what the day header two rows up
 * already says.
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

    // The pill outlives the finger by about a second: a drag that stops on a month is a decision about that
    // month, and it should be readable after the hand leaves the screen.
    LaunchedEffect(scrubbing) {
        if (scrubbing == null) return@LaunchedEffect
        delay(PILL_LINGER_MILLIS)
        scrubbing = null
    }

    val labelStride = TimelineRail.labelStride(
        monthCount = months.size,
        trackPx = trackPx,
        minPxPerLabel = with(density) { RailLabelMinHeight.toPx() },
    )
    val railDescription = stringResource(R.string.photos_date_rail)

    Box(
        modifier = modifier
            .width(RailWidth)
            .fillMaxHeight()
            .onSizeChanged { size -> trackPx = size.height.toFloat() }
            .semantics { contentDescription = railDescription }
            // A vertical drag, because that is what a rail is: one finger along a strip of months. Taking the
            // gesture also *claims* it, which matters for one reason — the grid under the rail would scroll on
            // the same movement, and a scrubber whose own drag scrolls the list it is scrubbing moves the
            // answer away from the question.
            .pointerInput(months, trackPx) {
                // Nothing can be divided by a track that has not been measured yet, which is the first frame.
                if (trackPx <= 0f) return@pointerInput
                // Height to fraction, fraction to index: both steps are [TimelineRail]'s arithmetic, which the
                // tests assert, so the only thing left in this file that can be wrong is the drawing.
                fun scrubTo(yPx: Float) {
                    val fraction = (yPx / trackPx).coerceIn(0f, 1f)
                    val index = TimelineRail.itemIndexFor(months, fraction)
                    val month = TimelineRail.monthFor(months, index)
                    if (month != null) scrubbing = Scrubbing(month.yearMonth, yPx)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(),
        ) {
            // A slice of the track per month, weighted by the items that month holds: the rail's shape is the
            // library's shape, so a third of the way down is a third of the way through the photos.
            months.forEachIndexed { position, month ->
                val active = month == activeMonth
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(month.itemCount.coerceAtLeast(1).toFloat()),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (active || position % labelStride == 0) {
                        Text(
                            text = monthLabel(month.yearMonth),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (active) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Visible,
                            modifier = Modifier.padding(end = TickGap),
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(RailTickWidth)
                            .height(if (active) TickHeight else TickHeight / 2)
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

        scrubbing?.let { where ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.inverseSurface,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(y = with(density) { (where.yPx - PillHalfHeight.toPx()).dp }),
            ) {
                Text(
                    text = monthLabel(where.month),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/** Where the finger is on the rail: the month to name, and how far down to name it. */
private class Scrubbing(val month: YearMonth, val yPx: Float)

@Composable
private fun monthLabel(month: YearMonth): String =
    formatMonth(month, showYear = month.year != YearMonth.now().year)

/** How long the scrub pill stays up after the finger leaves. */
private const val PILL_LINGER_MILLIS = 900L

private val PillHalfHeight: Dp = 16.dp
private val TickHeight: Dp = 14.dp
private val TickGap: Dp = 12.dp

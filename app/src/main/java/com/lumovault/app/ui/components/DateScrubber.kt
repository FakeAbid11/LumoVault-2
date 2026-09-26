package com.lumovault.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.ScrubSlot
import com.lumovault.app.domain.model.ScrollScrubber
import com.lumovault.app.ui.theme.ScrubberBubbleCorner
import com.lumovault.app.ui.theme.ScrubberBubbleGap
import com.lumovault.app.ui.theme.ScrubberEndInset
import com.lumovault.app.ui.theme.ScrubberHandleActiveWidth
import com.lumovault.app.ui.theme.ScrubberHandleCorner
import com.lumovault.app.ui.theme.ScrubberHandleHeight
import com.lumovault.app.ui.theme.ScrubberHandleWidth
import com.lumovault.app.ui.theme.ScrubberStripWidth
import com.lumovault.app.ui.theme.ScrubberVerticalInset
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SCRUB_BUBBLE_LINGER_MILLIS
import com.lumovault.app.ui.theme.SCRUB_HANDLE_IDLE_ALPHA
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * The timeline's date scrubber: one handle, dragged through a track, naming the day it is pointing at.
 *
 * This is the reference app's `FastScrollScrubber`, and it is worth being explicit about what it is *not*:
 * there are no tick marks, no month index and no letters. The handle's height is the viewport's share of the
 * list, its position is where the list is looking, and the bubble that appears while it is dragged says the
 * same string the day header above those photos says. That is the whole instrument, and it is why the strip
 * can be 44 dp wide and still cost the grid nothing but a 6 dp mark near the edge.
 *
 * The strip is a touch area, not a picture. It sits over the rightmost column of thumbnails — the reference
 * floats it in a `Stack` for exactly this reason — and it claims only vertical drags, so a tap still opens a
 * photo and a horizontal swipe still pages the timeline. The grid does not give up width for it, which is what
 * keeps the grid edge-to-edge.
 *
 * @param slots the days of the timeline, from [ScrollScrubber.slots].
 * @param itemCount how many grid items the list holds, including anything above the first day.
 * @param visibleItems roughly how many items fill the viewport; it sets the handle's height.
 * @param firstVisibleItemIndex the grid's current top item, which is where the handle sits at rest.
 * @param labelFor a day's own label, resolved by the caller because it is a string resource, not a number. It
 * is composable for the same reason: the caller reads those resources through the composition.
 * @param onScrub called with a grid index as the handle moves; the caller scrolls the real list.
 */
@Composable
fun DateScrubber(
    slots: List<ScrubSlot>,
    itemCount: Int,
    visibleItems: Int,
    firstVisibleItemIndex: Int,
    labelFor: @Composable (Long) -> String,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (itemCount <= 1) return

    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var trackPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var bubble by remember { mutableStateOf<Bubble?>(null) }

    // The bubble outlives the finger by most of a second, then goes: a drag that stops on a day is a decision
    // about that day, and it should still be readable after the hand leaves the screen.
    LaunchedEffect(bubble) {
        if (bubble == null) return@LaunchedEffect
        delay(SCRUB_BUBBLE_LINGER_MILLIS)
        bubble = null
    }

    val handleFraction = ScrollScrubber.handleFraction(visibleItems, itemCount)
    val handlePx = with(density) {
        (handleFraction * trackPx).coerceAtLeast(ScrubberHandleHeight.toPx())
    }
    val restTop = ScrollScrubber.handleTop(
        scrollFraction = ScrollScrubber.scrollFractionFor(firstVisibleItemIndex, visibleItems, itemCount),
        trackPx = trackPx,
        handlePx = handlePx,
    )
    val scrubberDescription = stringResource(R.string.photos_date_rail)

    Box(
        modifier = modifier
            .width(ScrubberStripWidth)
            .fillMaxHeight()
            .padding(vertical = ScrubberVerticalInset, horizontal = ScrubberEndInset)
            .onSizeChanged { size -> trackPx = size.height.toFloat() }
            .semantics { contentDescription = scrubberDescription }
            .pointerInput(slots, itemCount, trackPx) {
                if (trackPx <= 0f) return@pointerInput
                fun scrubTo(yPx: Float) {
                    val fraction = (yPx / trackPx).coerceIn(0f, 1f)
                    val index = ScrollScrubber.indexFor(fraction, itemCount)
                    val epochDay = ScrollScrubber.epochDayFor(slots, index)
                    if (epochDay != null) {
                        // A click per *day* crossed, not per frame: the reference does this, and the reason
                        // is that a tick per pixel is a buzz that tells you nothing you could not see. The
                        // label itself is resolved where it is drawn, because reading a string resource is a
                        // composition call and this is a gesture loop.
                        if (bubble?.epochDay != epochDay) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        bubble = Bubble(epochDay = epochDay, yPx = yPx)
                    }
                    onScrub(index)
                }
                detectVerticalDragGestures(
                    onDragStart = { position ->
                        dragging = true
                        scrubTo(position.y)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    scrubTo(change.position.y)
                }
            },
    ) {
        val topPx = if (dragging) {
            (bubble?.yPx ?: restTop) - handlePx / 2f
        } else {
            restTop
        }.coerceIn(0f, (trackPx - handlePx).coerceAtLeast(0f))

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset { IntOffset(0, topPx.roundToInt()) }
                .width(if (dragging) ScrubberHandleActiveWidth else ScrubberHandleWidth)
                .height(with(density) { handlePx.toDp() })
                .background(
                    if (dragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SCRUB_HANDLE_IDLE_ALPHA)
                    },
                    RoundedCornerShape(ScrubberHandleCorner),
                ),
        )

        bubble?.let { where ->
            Surface(
                shape = RoundedCornerShape(ScrubberBubbleCorner),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // The bubble is centred on the finger and clamped into the track. It hangs to the *left*
                    // of the strip because the strip is at the right edge of the screen, and an offset rather
                    // than a padding because the offset is measured in the track's own pixels.
                    .offset {
                        val half = ScrubberBubbleHalf.toPx()
                        val maxTop = (trackPx - half * 2f).coerceAtLeast(0f)
                        IntOffset(
                            x = -ScrubberBubbleGap.toPx().roundToInt(),
                            y = (where.yPx - half).coerceIn(0f, maxTop).roundToInt(),
                        )
                    }
                    .padding(horizontal = SpaceMd, vertical = SpaceXs),
            ) {
                Text(
                    text = labelFor(where.epochDay),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,
                )
            }
        }
    }
}

/** The day being named, its label, and how far down the track the finger is. */
private class Bubble(val epochDay: Long, val yPx: Float)

private val ScrubberBubbleHalf = 18.dp

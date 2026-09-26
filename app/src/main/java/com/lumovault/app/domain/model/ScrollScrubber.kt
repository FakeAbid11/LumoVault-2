package com.lumovault.app.domain.model

import kotlin.math.roundToInt

/** One day's run of grid items: where its header sits, how many items it and its photos take, and which day. */
data class ScrubSlot(
    val firstItemIndex: Int,
    val itemCount: Int,
    val epochDay: Long,
)

/**
 * The arithmetic behind the timeline's scrubber.
 *
 * The reference app's scrubber is a proportional scrollbar, not an index: one handle whose height is the
 * viewport's share of the list, dragged through a track, jumping the list to `maxScroll * progress` and
 * naming the day under the finger in a bubble. That is two functions — a fraction to an item index and an
 * index to a day — and both live here, out of the composable, for the same reason the month rail's maths did:
 * a scrubber that jumps to the wrong place, names the day above the one it points at, or throws on a library
 * of one is invisible in a screenshot and obvious in a test, and the drag itself cannot be exercised at all
 * without a finger.
 */
object ScrollScrubber {
    /**
     * The days of the timeline as grid slots, in the order the grid renders them.
     *
     * [firstItemIndex] is where the first day header actually sits, so anything above the timeline — a
     * limited-access notice — shifts every slot along by its own item count rather than being silently
     * ignored, and the scrubber stays honest about what the list can scroll to.
     */
    fun slots(days: List<MediaDay>, firstItemIndex: Int = 0): List<ScrubSlot> {
        if (days.isEmpty()) return emptyList()
        var cursor = firstItemIndex
        return days.map { day ->
            val slot = ScrubSlot(
                firstItemIndex = cursor,
                itemCount = HEADER_ITEMS_PER_DAY + day.items.size,
                epochDay = day.epochDay,
            )
            cursor += slot.itemCount
            slot
        }
    }

    /** One day of a timeline is a header plus its photos. */
    const val HEADER_ITEMS_PER_DAY = 1

    /**
     * The item index for a position along the track, where 0 is the top and 1 the bottom.
     *
     * Out-of-range and non-finite fractions are clamped rather than rejected: the value comes from a finger's
     * position in pixels, and the last pixel of a track has been rounding past 1.0 since scrollbars were
     * invented. An empty list answers 0 because there is nothing else it could mean.
     */
    fun indexFor(fraction: Float, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val last = itemCount - 1
        if (last == 0) return 0
        val clamped = if (!fraction.isFinite()) 0f else fraction.coerceIn(0f, 1f)
        return (clamped * last).roundToInt()
    }

    /** Which day an item index belongs to, or null when there is no timeline to ask. */
    fun epochDayFor(slots: List<ScrubSlot>, itemIndex: Int): Long? {
        if (slots.isEmpty()) return null
        // Above the first day is the first day: a notice strip over the timeline is still the newest day's
        // neighbourhood, and "no label" would be a worse answer than the right one.
        if (itemIndex < slots.first().firstItemIndex) return slots.first().epochDay
        slots.forEach { slot ->
            if (itemIndex < slot.firstItemIndex + slot.itemCount) return slot.epochDay
        }
        return slots.last().epochDay
    }

    /**
     * How much of the track the handle occupies: the viewport's share of the list, with a floor.
     *
     * The floor is what keeps a ten-thousand-photo library usable — at its true proportion the handle would be
     * a couple of pixels tall and no finger could hold it — and the ceiling is what stops a three-photo library
     * from showing a handle that cannot move, which reads as a control that is broken rather than a list that
     * is short.
     */
    fun handleFraction(visibleItems: Int, itemCount: Int): Float {
        if (itemCount <= 0 || visibleItems <= 0) return 1f
        return (visibleItems.toFloat() / itemCount).coerceIn(MinHandleFraction, 1f)
    }

    /**
     * The top edge of the handle, given how far down the list the viewport has scrolled.
     *
     * The handle travels the track minus its own height, so at the top of a long list it sits at the top of
     * the track and at the bottom it is fully visible at the bottom — a handle that ran to the end of the
     * track while its own height hung past it would be a scrollbar that scrolls off its own screen.
     */
    fun handleTop(scrollFraction: Float, trackPx: Float, handlePx: Float): Float {
        if (trackPx <= 0f) return 0f
        val travel = (trackPx - handlePx).coerceAtLeast(0f)
        val clamped = if (!scrollFraction.isFinite()) 0f else scrollFraction.coerceIn(0f, 1f)
        return clamped * travel
    }

    /** How far down the list a viewport sitting at [itemIndex] of [itemCount] has travelled. */
    fun scrollFractionFor(itemIndex: Int, visibleItems: Int, itemCount: Int): Float {
        if (itemCount <= 0) return 0f
        val last = (itemCount - visibleItems).coerceAtLeast(1)
        return ((itemIndex - visibleItems / 2f) / last).coerceIn(0f, 1f)
    }

    /** Two per cent of the track: enough for a finger on a library of tens of thousands. */
    const val MinHandleFraction = 0.02f
}

package com.lumovault.app.domain.model

import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One month on the timeline's date rail.
 *
 * A month is described by where its first day header sits in the grid and how many grid items the month
 * occupies — a header per day plus one item per photo. Item counts rather than pixels because the rail's job
 * is to hand the grid an index to scroll to, and pixels belong to a laid-out list this file cannot see.
 */
data class RailMonth(
    val yearMonth: YearMonth,
    val firstItemIndex: Int,
    val itemCount: Int,
)

/**
 * The arithmetic behind the rail: a fraction of its height to a grid index, and a grid index back to the
 * month whose label should be lit.
 *
 * Kept pure and separate from the composable for the same reason [MediaTimeline]'s bucketing is: the thing a
 * user can get wrong here is not the drawing, it is the mapping. A rail that jumps to the wrong month, one
 * that moves the grid by two items when dragged a third of the way down, or one that crashes on a library of
 * one is invisible in a screenshot and obvious in a test — and the drag itself cannot be exercised at all
 * without a finger, which is precisely the argument for keeping the numbers out of the gesture code.
 *
 * Weighting is by item count, so a month of 900 photos gets ninety times the rail a month of ten gets. That
 * is what a person scrubbing a ten-year library is looking for: the rail's shape is the library's shape.
 */
object TimelineRail {
    /** One day of the timeline contributes a header plus its photos. */
    const val HEADER_ITEMS_PER_DAY = 1

    /**
     * The months covered by [days], in the order the grid renders them.
     *
     * [firstItemIndex] is where the first day header actually sits, so anything above the timeline — a
     * limited-access notice, a section label — shifts every month along by its own item count rather than
     * being silently ignored, and the rail stays honest about what the grid can scroll to.
     *
     * Days are counted as [MediaDay]s give them: a header plus one item per photo. Two days in the same month
     * land in one entry, and the rail is a month per tick because a day per tick would be a list of thousands
     * on the longest libraries — the opposite of a shortcut to anywhere.
     */
    fun months(days: List<MediaDay>, firstItemIndex: Int = 0): List<RailMonth> {
        if (days.isEmpty()) return emptyList()

        // Grouped by month, but in grid order and with the running item count attached — a plain `groupBy`
        // would lose the order the days arrive in, and the days are newest-first.
        val grouped = LinkedHashMap<YearMonth, Int>()
        days.forEach { day ->
            val month = YearMonth.from(LocalDate.ofEpochDay(day.epochDay))
            grouped[month] = (grouped[month] ?: 0) + HEADER_ITEMS_PER_DAY + day.items.size
        }

        val months = ArrayList<RailMonth>(grouped.size)
        var cursor = firstItemIndex
        grouped.forEach { (month, count) ->
            months += RailMonth(yearMonth = month, firstItemIndex = cursor, itemCount = count)
            cursor += count
        }
        return months
    }

    /**
     * The grid index for a position along the rail, where 0 is the top and 1 the bottom.
     *
     * Out-of-range fractions are clamped rather than rejected: the value comes from a finger's position in
     * pixels, and the last pixel of a track has been rounding past 1.0 since scrollbars were invented.
     *
     * Linear in items rather than in rows, because `months()` lays the months down contiguously in the grid
     * and counts every item between them: a share of the rail is then the same share of the library's items,
     * which is the weighting the comment above promises. Which month that index belongs to is
     * [monthFor]'s question, not this one's.
     */
    fun itemIndexFor(months: List<RailMonth>, fraction: Float): Int {
        if (months.isEmpty()) return 0
        val first = months.first().firstItemIndex
        val total = months.sumOf { it.itemCount }
        if (total <= 0) return first
        val clamped = if (!fraction.isFinite()) 0f else fraction.coerceIn(0f, 1f)
        val last = first + total - 1
        return (first + clamped * (last - first)).roundToInt()
    }

    /** The month a grid index is looking at, or null when there is no rail to ask. */
    fun monthFor(months: List<RailMonth>, itemIndex: Int): RailMonth? {
        if (months.isEmpty()) return null
        // Above the first month is the first month: a header row or a notice strip above the timeline is
        // still the newest month's neighbourhood, and "no label" would be a worse answer than the right one.
        if (itemIndex <= months.first().firstItemIndex) return months.first()
        months.forEach { month ->
            if (itemIndex < month.endIndex()) return month
        }
        return months.last()
    }

    /** One past the month's last item — the first index of the next one, or the end of the grid. */
    fun RailMonth.endIndex(): Int = firstItemIndex + itemCount

    /**
     * How many months to skip between drawn labels, so that no two labels want the same pixels.
     *
     * A ten-year library is 120 ticks, and a phone gives the rail eight hundred-odd pixels of it: a label
     * each would be a smear. Asking for a stride rather than measuring the text keeps this a number that can
     * be tested — and the answer for a short rail is 1, which is what the four-month library should look
     * like. Anything unmeasurable (a track that has not been laid out yet, zero months) answers 1 so the
     * first frame is not a rail of nothing.
     */
    fun labelStride(monthCount: Int, trackPx: Float, minPxPerLabel: Float): Int {
        if (monthCount <= 0 || trackPx <= 0f || minPxPerLabel <= 0f) return 1
        val fits = (trackPx / minPxPerLabel).toInt().coerceAtLeast(1)
        return ceil(monthCount / fits.toDouble()).roundToInt().coerceAtLeast(1)
    }
}

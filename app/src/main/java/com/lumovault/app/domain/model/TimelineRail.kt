package com.lumovault.app.domain.model

import java.time.LocalDate
import java.time.YearMonth
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
     * Where a month sits on the rail, as a fraction of its height — the inverse of [itemIndexFor].
     *
     * The centre of the month's own slice, not its start, because this number draws a marker beside a month
     * rather than a boundary between two: a pill that names March while pointing at the first item of March
     * reads as one month early. Weighted by items for the same reason [itemIndexFor] is — the rail's shape is
     * the library's shape, so a share of the items is the same share of the track.
     *
     * Null for a month that is not on the rail: callers pass what [monthFor] answered, and a month from a
     * superseded list is the one case where drawing anything would be drawing a lie. Zero was the old answer,
     * and zero is exactly where the first month lives — an off-rail month was indistinguishable from the top
     * of the track, so a stale label pinned itself to the newest month after the window grew.
     */
    fun fractionFor(months: List<RailMonth>, month: RailMonth): Float? {
        val total = months.sumOf { it.itemCount }
        if (total <= 0) return null
        val position = months.indexOf(month)
        if (position < 0) return null
        val itemsBefore = months.subList(0, position).sumOf { it.itemCount }
        val centre = itemsBefore + month.itemCount / 2f
        return (centre / total).coerceIn(0f, 1f)
    }
}

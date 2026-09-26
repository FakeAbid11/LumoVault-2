package com.lumovault.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * The date rail's arithmetic, asserted instead of felt.
 *
 * A scrubber is the rare control whose whole job is a mapping — height in, index out — and the mapping can be
 * wrong in ways no screenshot shows: a rail that moves two items when dragged a third of the way down, one
 * that names February while the grid is looking at August, one that throws on the third phone with a single
 * photo on it. None of that is discoverable without a finger, so nothing about it is left to a finger: the
 * gesture code in `DateRail` measures a position and asks these functions what it means.
 *
 * Every expected count below is worked out in the open, because the interesting claim is not that the maths
 * runs but *what it counts*: a day on the rail is a header plus one item per photo, so a March holding two
 * photos on one day and one on another is five grid items, not two, and not three.
 */
class TimelineRailTest {
    /** 2024-03-20 with two photos: `(1 header + 2) = 3` grid items. */
    private val marchSecond = day(epochDay = epochDay(2024, 3, 20), photos = 2)

    /** 2024-03-12 with one photo: 2 items. March therefore spans items 0..4. */
    private val marchTwelfth = day(epochDay = epochDay(2024, 3, 12), photos = 1)

    /** 2024-02-28 with four photos: 5 items, starting at index 5. */
    private val february = day(epochDay = epochDay(2024, 2, 28), photos = 4)

    /** Newest first, exactly as `groupByDay` hands the days to the grid. */
    private val library = listOf(marchSecond, marchTwelfth, february)

    private fun day(epochDay: Long, photos: Int) = MediaDay(
        epochDay = epochDay,
        items = (1L..photos.toLong()).map { index ->
            Media(
                id = epochDay * 100 + index,
                contentUri = "content://media/external/file/${epochDay * 100 + index}",
                type = MediaType.Photo,
                mimeType = "image/jpeg",
                displayName = "IMG_$index.jpg",
                relativePath = "DCIM/Camera/",
                sizeBytes = 1_000L,
                dateAddedSeconds = epochDay * 86_400L,
                dateModifiedSeconds = epochDay * 86_400L,
                width = 4_000,
                height = 3_000,
                durationMillis = null,
            )
        },
    )

    private fun epochDay(year: Int, month: Int, dayOfMonth: Int): Long =
        LocalDate.of(year, month, dayOfMonth).toEpochDay()

    @Test
    fun aMonthCountsItsHeadersAndItsPhotos() {
        val months = TimelineRail.months(library)

        assertEquals("two March days and one February day make two ticks", 2, months.size)
        assertEquals(
            "March is 3 + 2 items and February is 5, newest month first",
            listOf(YearMonth.of(2024, 3) to 5, YearMonth.of(2024, 2) to 5),
            months.map { it.yearMonth to it.itemCount },
        )
        assertEquals("and the indexes are the grid's own rows, contiguous from the top",
            listOf(0, 5), months.map { it.firstItemIndex })
    }

    @Test
    fun aLeadingNoticeShiftsEveryMonthAlong() {
        // Android 14's partial grant puts a notice row above the timeline. It is a grid item like any other,
        // and a rail that ignored it would scroll one row away from the month it named — for every month,
        // forever.
        val months = TimelineRail.months(library, firstItemIndex = 1)

        assertEquals(listOf(1, 6), months.map { it.firstItemIndex })
        assertEquals(1, TimelineRail.itemIndexFor(months, fraction = 0f))
    }

    @Test
    fun theEndsOfTheRailAreTheEndsOfTheLibrary() {
        val months = TimelineRail.months(library)
        // Ten items, so the last one is index 9 — the bottom of the rail must point at the last row that
        // exists, not one past it.
        val lastItem = 9

        assertEquals(0, TimelineRail.itemIndexFor(months, fraction = 0f))
        assertEquals(lastItem, TimelineRail.itemIndexFor(months, fraction = 1f))
        assertEquals("a finger that overshoots the track is clamped, not wrapped",
            lastItem, TimelineRail.itemIndexFor(months, fraction = 1.8f))
        assertEquals(0, TimelineRail.itemIndexFor(months, fraction = -3f))
        assertEquals("a NaN is a broken measurement, not a jump to nowhere",
            0, TimelineRail.itemIndexFor(months, fraction = Float.NaN))
    }

    @Test
    fun halfwayDownTheRailIsHalfwayThroughThePhotos() {
        val months = TimelineRail.months(library)

        // The two months hold five items each, so the exact middle of the rail is the first item of the older
        // month — and a month boundary is where the rail's weighting is visible: an unweighted rail would put
        // index 5 somewhere near two thirds down, because February's photos live in one day.
        assertEquals(5, TimelineRail.itemIndexFor(months, fraction = 0.5f))
        assertSame(months[0], TimelineRail.monthFor(months, 4))
        assertSame("the highlight flips on the same item the grid scrolls to",
            months[1], TimelineRail.monthFor(months, 5))
    }

    @Test
    fun positionsOutsideTheMonthsReadAsTheNearestOne() {
        val months = TimelineRail.months(library)

        assertSame("above the first month is the newest month, not nothing",
            months[0], TimelineRail.monthFor(months, -1))
        assertSame(months[0], TimelineRail.monthFor(months, 0))
        assertSame("and below the last one the rail stops rather than inventing a month",
            months[1], TimelineRail.monthFor(months, 999))
    }

    @Test
    fun aLibraryOfOnePhotoStillHasARailThatMeansSomething() {
        val months = TimelineRail.months(listOf(day(epochDay = epochDay(2024, 5, 9), photos = 1)))

        assertEquals(1, months.size)
        assertEquals("one header and one cell", 2, months[0].itemCount)
        assertEquals(0, TimelineRail.itemIndexFor(months, fraction = 0f))
        assertEquals(1, TimelineRail.itemIndexFor(months, fraction = 1f))
        assertSame(months[0], TimelineRail.monthFor(months, 0))
    }

    @Test
    fun anEmptyLibraryDrawsNoRailAndRefusesToJump() {
        val months = TimelineRail.months(emptyList())

        assertEquals(emptyList<RailMonth>(), months)
        assertEquals("nothing to scroll to", 0, TimelineRail.itemIndexFor(months, fraction = 0.5f))
        assertNull(TimelineRail.monthFor(months, 0))
    }

    @Test
    fun twoDaysInOneMonthAreOneTickNotTwo() {
        // One tick per month is the normal case, not an edge: a year of photos is twelve marks on a strip a
        // finger wide, where a mark per day would be 365 on the rail that exists to make scrolling shorter.
        val months = TimelineRail.months(
            listOf(
                day(epochDay = epochDay(2024, 6, 1), photos = 3),
                day(epochDay = epochDay(2024, 6, 2), photos = 3),
                day(epochDay = epochDay(2024, 5, 31), photos = 1),
            ),
        )

        assertEquals(listOf(YearMonth.of(2024, 6), YearMonth.of(2024, 5)), months.map { it.yearMonth })
        assertEquals("June holds both days' items in one slice of the track",
            listOf(8, 2), months.map { it.itemCount })
    }

    @Test
    fun aMonthIsAnnouncedAtTheMiddleOfItsOwnSliceOfTheTrack() {
        // Two months of equal weight split the rail in half, so their centres are a quarter of the way down
        // and three quarters. The pill is drawn at these numbers: a month named at fraction 0 would float
        // above the first tick it is supposed to be labelling, and one named at its last item would sit over
        // the month below it. (Doubles rather than floats because that is the `assertEquals` overload JUnit
        // has had longest.)
        val months = TimelineRail.months(library)

        assertEquals(0.25, requireNotNull(TimelineRail.fractionFor(months, months[0])).toDouble(), 0.001)
        assertEquals(0.75, requireNotNull(TimelineRail.fractionFor(months, months[1])).toDouble(), 0.001)
    }

    @Test
    fun theCentreOfAWholeLibraryIsItsOwnMiddle() {
        val one = TimelineRail.months(listOf(day(epochDay = epochDay(2024, 3, 1), photos = 9)))

        assertEquals(
            "one month on the rail is the whole rail",
            0.5,
            requireNotNull(TimelineRail.fractionFor(one, one[0])).toDouble(),
            0.001,
        )
        // Null, never zero: zero *is* the neighbourhood of the first month, and a caller that drew what it
        // got would pin a stale month's name to the top of the track. Off the rail means no name at all.
        assertNull(
            "a month from a list it is not in names nothing",
            TimelineRail.fractionFor(one, RailMonth(YearMonth.of(2020, 1), firstItemIndex = 0, itemCount = 1)),
        )
        assertNull(
            "an empty rail cannot host any month's announcement either",
            TimelineRail.fractionFor(emptyList(), RailMonth(YearMonth.of(2024, 3), 0, 0)),
        )
    }

    @Test
    fun aFractionAndAnIndexRoundTripToTheSameMonth() {
        // The drag asks the other question — height to index — and the two must agree, or the name beside the
        // finger is not the name of the month the grid has just scrolled to.
        val months = TimelineRail.months(library)

        months.forEach { month ->
            val centre = requireNotNull(TimelineRail.fractionFor(months, month))
            val index = TimelineRail.itemIndexFor(months, centre)
            assertEquals(month.yearMonth, TimelineRail.monthFor(months, index)?.yearMonth)
        }
    }

    @Test
    fun theRailAndTheGridCountTheSameItems() {
        // `PhotosScreen` adds up its own rows for load-ahead; if the rail's total disagreed with that sum, the
        // bottom tick would point past the end of the list and the highlight would be wrong on the last
        // screenful. Both are "one header plus one item per photo, per day", and this pins that they stay so.
        val months = TimelineRail.months(library)
        val renderedRows = library.sumOf { it.items.size } + library.size

        assertEquals(10, renderedRows)
        assertEquals(renderedRows, months.sumOf { it.itemCount })
        assertEquals(renderedRows - 1, TimelineRail.itemIndexFor(months, fraction = 1f))
    }
}

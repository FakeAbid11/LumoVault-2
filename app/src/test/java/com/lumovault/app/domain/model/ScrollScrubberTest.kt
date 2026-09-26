package com.lumovault.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scrubber's arithmetic, asserted instead of felt.
 *
 * A scrollbar is the rare control whose whole job is a mapping — height in, index out, index back to a day —
 * and the mapping can be wrong in ways no screenshot shows: a handle that names the day above the one it
 * points at, a track that jumps two items when dragged a third of the way down, a library of one that
 * crashes. None of it is discoverable without a finger, so nothing about it is left to a finger:
 * `DateScrubber` measures a position and asks these functions what it means.
 */
class ScrollScrubberTest {
    /** Three days, newest first, exactly as `groupByDay` hands them to the grid: 4 + 2 + 6 items. */
    private val library = listOf(
        day(epochDay = 102, photos = 3),
        day(epochDay = 101, photos = 1),
        day(epochDay = 100, photos = 5),
    )

    private val slots = ScrollScrubber.slots(library)

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

    @Test
    fun aDayCountsItsHeaderAndItsPhotosAndSlotsAreContiguous() {
        assertEquals(listOf(4, 2, 6), slots.map { it.itemCount })
        assertEquals(listOf(0, 4, 6), slots.map { it.firstItemIndex })
        assertEquals(listOf(102L, 101L, 100L), slots.map { it.epochDay })
    }

    @Test
    fun anythingAboveTheTimelineShiftsEveryDayAlong() {
        // The Android 14 limited-access notice is a rendered item, so a scrubber that ignored it would name
        // the right day and scroll to the row above it — one item off, on every single drag.
        val withNotice = ScrollScrubber.slots(library, firstItemIndex = 1)

        assertEquals(listOf(1, 5, 7), withNotice.map { it.firstItemIndex })
        assertEquals(12, withNotice.sumOf { it.itemCount })
    }

    @Test
    fun theEndsOfTheTrackAreTheEndsOfTheList() {
        val total = slots.sumOf { it.itemCount }

        assertEquals(0, ScrollScrubber.indexFor(0f, total))
        assertEquals(total - 1, ScrollScrubber.indexFor(1f, total))
        assertEquals("past the end is the last item, not an index error",
            total - 1, ScrollScrubber.indexFor(1.8f, total))
        assertEquals(0, ScrollScrubber.indexFor(-3f, total))
        assertEquals(0, ScrollScrubber.indexFor(Float.NaN, total))
        assertEquals(0, ScrollScrubber.indexFor(0.5f, 0))
        assertEquals("a one-item list cannot be dragged anywhere", 0, ScrollScrubber.indexFor(0.9f, 1))
    }

    @Test
    fun theMiddleOfTheTrackIsTheMiddleOfTheLibrary() {
        val total = slots.sumOf { it.itemCount }

        // Halfway through twelve items is index 6 — the first item of the third day, which is the day the
        // bubble must therefore name.
        assertEquals(6, ScrollScrubber.indexFor(0.5f, total))
        assertEquals(100L, ScrollScrubber.epochDayFor(slots, ScrollScrubber.indexFor(0.5f, total)))
        assertEquals(102L, ScrollScrubber.epochDayFor(slots, 0))
        assertEquals("the boundary item belongs to the day that starts on it",
            101L, ScrollScrubber.epochDayFor(slots, 4))
        assertEquals(100L, ScrollScrubber.epochDayFor(slots, 9_000))
        assertNull(ScrollScrubber.epochDayFor(emptyList(), 0))
    }

    @Test
    fun theHandleIsTheViewportWithAFloorAndACeiling() {
        assertEquals("everything on screen is a full-height handle",
            1f, ScrollScrubber.handleFraction(visibleItems = 20, itemCount = 20), 0.001f)
        assertEquals(0.25f, ScrollScrubber.handleFraction(visibleItems = 5, itemCount = 20), 0.001f)
        assertEquals("a ten-thousand-photo library still gives a finger something to hold",
            ScrollScrubber.MinHandleFraction,
            ScrollScrubber.handleFraction(visibleItems = 12, itemCount = 12_000),
            0.001f)
        assertEquals(1f, ScrollScrubber.handleFraction(visibleItems = 0, itemCount = 0), 0.001f)
    }

    @Test
    fun theHandleTravelsTheTrackMinusItsOwnHeight() {
        assertEquals(0f, ScrollScrubber.handleTop(0f, trackPx = 800f, handlePx = 100f), 0.001f)
        assertEquals("at the bottom of the list the handle is fully inside the track",
            700f, ScrollScrubber.handleTop(1f, trackPx = 800f, handlePx = 100f), 0.001f)
        assertEquals(350f, ScrollScrubber.handleTop(0.5f, trackPx = 800f, handlePx = 100f), 0.001f)
        assertEquals("a handle taller than its track sits at the top of it",
            0f, ScrollScrubber.handleTop(1f, trackPx = 40f, handlePx = 100f), 0.001f)
        assertEquals(0f, ScrollScrubber.handleTop(0.5f, trackPx = 0f, handlePx = 100f), 0.001f)
        assertEquals(0f, ScrollScrubber.handleTop(Float.NaN, trackPx = 800f, handlePx = 100f), 0.001f)
    }

    @Test
    fun aScrollAtTheTopAndTheBottomReadsAsBothEnds() {
        val total = slots.sumOf { it.itemCount }

        assertEquals(0f, ScrollScrubber.scrollFractionFor(0, visibleItems = 6, itemCount = total), 0.001f)
        assertEquals(1f, ScrollScrubber.scrollFractionFor(total - 1, visibleItems = 6, itemCount = total), 0.001f)
        assertEquals("nothing to scroll is not a division by zero",
            0f, ScrollScrubber.scrollFractionFor(0, visibleItems = 6, itemCount = 4), 0.001f)
    }
}

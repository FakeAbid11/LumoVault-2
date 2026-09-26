package com.lumovault.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The timeline grouping *is* the Photos screen's structure, so these pin what a user sees: newest
 * day first, items inside a day in query order, and a day boundary drawn by the zone rather than by
 * raw second counts.
 */
class MediaTimelineTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    private fun media(id: Long, addedSeconds: Long) = Media(
        id = id,
        contentUri = "content://media/external/file/$id",
        type = MediaType.Photo,
        mimeType = "image/jpeg",
        displayName = "IMG_$id.jpg",
        relativePath = "DCIM/Camera/",
        sizeBytes = 1000L,
        dateAddedSeconds = addedSeconds,
        dateModifiedSeconds = addedSeconds,
        width = 4000,
        height = 3000,
        durationMillis = null,
    )

    @Test
    fun `an empty library groups to no days`() {
        assertEquals(emptyList<MediaDay>(), emptyList<Media>().groupByDay(utc))
    }

    @Test
    fun `items from the same day share one bucket and keep their order`() {
        val sameDay = listOf(
            media(id = 5, addedSeconds = NOON),
            media(id = 4, addedSeconds = NOON + 3_600),
        )

        val days = sameDay.groupByDay(utc)

        assertEquals(1, days.size)
        assertEquals(listOf(5L, 4L), days.single().items.map { it.id })
    }

    @Test
    fun `newest day comes first`() {
        val items = listOf(
            media(id = 1, addedSeconds = 3 * DAY_SECONDS),
            media(id = 2, addedSeconds = 1 * DAY_SECONDS),
            media(id = 3, addedSeconds = 2 * DAY_SECONDS),
        )

        assertEquals(listOf(3L, 2L, 1L), items.groupByDay(utc).map { it.epochDay })
    }

    @Test
    fun `one second before midnight still belongs to the previous day`() {
        val justBeforeMidnight = 2 * DAY_SECONDS - 1

        val days = listOf(media(id = 1, addedSeconds = justBeforeMidnight)).groupByDay(utc)

        assertEquals(1L, days.single().epochDay)
    }

    @Test
    fun `timestamps before the epoch land on a negative day instead of the first bucket`() {
        val days = listOf(media(id = 1, addedSeconds = -1)).groupByDay(utc)

        assertEquals(-1L, days.single().epochDay)
    }

    private companion object {
        const val DAY_SECONDS = 86_400L
        const val NOON = 12L * 3_600L
    }
}

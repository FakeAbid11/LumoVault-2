package com.lumovault.app.domain.model

import java.time.Instant
import java.time.ZoneId

/**
 * The Photos timeline: media bucketed by the day it was **added to the device**, newest first.
 *
 * Not the capture day — a photo taken in 2015 and imported yesterday belongs to the day the library grew,
 * which is [Media]'s documented rule; the capture date belongs to the viewer and the map, where the
 * photo's own EXIF is the subject.
 *
 * Grouping lives here rather than in the UI so the bucketing rule is testable and so a later
 * "Recently Added" or date-jump feature reuses the same definition of "same day".
 */
data class MediaDay(
    val epochDay: Long,
    val items: List<Media>,
)

/** Buckets already-ordered media by local day, preserving the incoming order inside each bucket. */
fun List<Media>.groupByDay(zone: ZoneId = ZoneId.systemDefault()): List<MediaDay> =
    groupBy { media -> epochDayOf(media.dateAddedSeconds, zone) }
        .map { (epochDay, items) -> MediaDay(epochDay = epochDay, items = items) }
        .sortedBy { day -> day.epochDay }
        .reversed()

/**
 * Seconds since epoch to a day number. Negative values are floored rather than truncated, so a
 * pre-1970 timestamp cannot land in the wrong bucket.
 */
internal fun epochDayOf(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochSecond(epochSeconds).atZone(zone).toLocalDate().toEpochDay()

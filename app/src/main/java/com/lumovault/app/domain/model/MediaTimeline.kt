package com.lumovault.app.domain.model

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Photos timeline: media bucketed by the day it was captured or added, newest day first.
 *
 * Grouping lives here rather than in the UI so the bucketing rule is testable and so a later
 * "Recently Added" or date-jump feature reuses the same definition of "same day".
 */
data class MediaDay(
    val epochDay: Long,
    val items: List<Media>,
) {
    /**
     * How the day should be worded. Relative labels ("Today", "Yesterday") are computed against a
     * supplied [today] rather than read from the clock inside the function, so a test can pin the
     * boundary and the caller can recompute after the device date changes.
     */
    fun relation(today: LocalDate, zone: ZoneId): DayRelation {
        val day = LocalDate.ofEpochDay(epochDay)
        val difference = today.toEpochDay() - day.toEpochDay()
        return when {
            difference == 0L -> DayRelation.Today
            difference == 1L -> DayRelation.Yesterday
            day.year == today.year -> DayRelation.WithinYear
            else -> DayRelation.Older
        }
    }
}

enum class DayRelation { Today, Yesterday, WithinYear, Older }

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

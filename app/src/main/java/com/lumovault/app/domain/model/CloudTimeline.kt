package com.lumovault.app.domain.model

import java.time.ZoneId

/**
 * The Cloud timeline: remote items bucketed by the day Telegram reports, newest day first.
 *
 * A separate type from [MediaDay] because the two carry different provenance — a local day comes
 * from MediaStore's added time, a cloud day from a message timestamp — and PRD section 42 insists
 * those are not the same claim. Sharing one bucket type would let a later change treat them as
 * interchangeable.
 */
data class CloudDay(
    val epochDay: Long,
    val items: List<CloudMedia>,
)

/** Buckets already-ordered remote items by local day, preserving incoming order inside each bucket. */
fun List<CloudMedia>.groupIntoDays(zone: ZoneId = ZoneId.systemDefault()): List<CloudDay> =
    groupBy { item -> epochDayOf(item.dateSeconds, zone) }
        .map { (epochDay, items) -> CloudDay(epochDay = epochDay, items = items) }
        .sortedBy { day -> day.epochDay }
        .reversed()

/**
 * Counts for the Cloud header, largest first.
 *
 * Returns types rather than words: "photos" is user-facing text and belongs in resources, so this
 * layer's job stops at "3,200 of this kind".
 */
fun List<CloudTypeCount>.cloudCounts(): List<Pair<MediaType, Int>> =
    mapNotNull { count ->
        val type = MediaType.entries.firstOrNull { it.storageKey == count.mediaType } ?: return@mapNotNull null
        type to count.itemCount
    }
        .filter { (_, items) -> items > 0 }
        .sortedByDescending { (_, items) -> items }

package com.lumovault.lumovault.features.metadata.domain.model

import com.lumovault.lumovault.features.metadata.data.util.Timestamps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.YearMonth

/**
 * A time-bucketed group of media items, keyed `YYYY/MM`.
 *
 * Ported from lib/features/metadata/data/models/metadata_partition.dart.
 * Partitions keep sync documents small and let the engine upload only the
 * month that changed instead of the whole library.
 */
data class MetadataPartition(
    /** `YYYY/MM`, e.g. `2026/07`. */
    val id: String,
    val periodStart: Instant,
    val periodEnd: Instant,
    val items: List<PartitionItem>,
    val lastModified: Instant,
) {
    /** Content digest of this partition's items. */
    fun computeHash(): String = PartitionItem.hashItems(items)

    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("period_start", Timestamps.format(periodStart))
        put("period_end", Timestamps.format(periodEnd))
        put("last_modified", Timestamps.format(lastModified))
        put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    }

    companion object {
        /**
         * Partition key for an item captured at [instant]. Zero-padded month.
         *
         * Membership is derived *solely* from this key, so it and
         * [dateFromPartitionKey] must stay exact inverses.
         */
        fun partitionKeyFromDate(instant: Instant): String {
            val ym = YearMonth.from(instant.atZone(java.time.ZoneOffset.UTC))
            return "%04d/%02d".format(ym.year, ym.monthValue)
        }

        /**
         * Inverse of [partitionKeyFromDate]. Falls back to a sane default for an
         * unparseable key rather than throwing — a corrupt key must not kill a
         * sync pass.
         */
        fun dateFromPartitionKey(key: String): Instant {
            val parts = key.split('/')
            val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val month = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
            return YearMonth.of(year, month).atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)
        }

        /** The key after [key], handling the December → January rollover. */
        fun nextPartitionKey(key: String): String {
            val parts = key.split('/')
            val year = parts.getOrNull(0)?.toIntOrNull() ?: 2026
            val month = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 12) ?: 1
            val next = YearMonth.of(year, month).plusMonths(1)
            return "%04d/%02d".format(next.year, next.monthValue)
        }

        /**
         * Parses a partition document, or null on *any* error. Losing one
         * partition document is recoverable from the channel; taking down the
         * sync pass is not.
         */
        fun fromJsonString(text: String): MetadataPartition? = try {
            fromJson(Json.parseToJsonElement(text).jsonObject)
        } catch (_: Throwable) {
            null
        }

        fun fromJson(obj: JsonObject): MetadataPartition? = try {
            val id = obj["id"]?.jsonPrimitive?.content ?: return null
            val items = (obj["items"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonObject)?.let(PartitionItem::fromJson) }
                .orEmpty()
            MetadataPartition(
                id = id,
                periodStart = Timestamps.parseOrNull(obj["period_start"]?.jsonPrimitive?.content)
                    ?: dateFromPartitionKey(id),
                periodEnd = Timestamps.parseOrNull(obj["period_end"]?.jsonPrimitive?.content)
                    ?: dateFromPartitionKey(nextPartitionKey(id)),
                items = items,
                lastModified = Timestamps.parseOrNull(obj["last_modified"]?.jsonPrimitive?.content)
                    ?: Instant.now(),
            )
        } catch (_: Throwable) {
            null
        }

        /** Creates a fresh partition for [key] holding a single [item]. */
        fun forItem(key: String, item: PartitionItem, now: Instant = Instant.now()): MetadataPartition {
            val start = dateFromPartitionKey(key)
            val end = dateFromPartitionKey(nextPartitionKey(key))
            return MetadataPartition(id = key, periodStart = start, periodEnd = end,
                items = listOf(item), lastModified = now)
        }
    }
}

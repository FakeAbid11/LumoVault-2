package com.lumovault.lumovault.features.metadata.data.util

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Timestamp (de)serialization that matches the Dart original byte-for-byte.
 *
 * Dart's `DateTime.toUtc().toIso8601String()` always emits millisecond
 * precision: `2026-01-15T00:00:00.000Z`. Kotlin's `Instant.toString()` emits
 * nanosecond precision and omits the fraction entirely when it is zero
 * (`2026-01-15T00:00:00Z`). The strings differ, so the *digest* of anything
 * hashed from them would differ too — every dirty check across the two
 * implementations would silently misfire. This formatter pins Dart's format.
 */
object Timestamps {

    private val formatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSX").withZone(java.time.ZoneOffset.UTC)

    /** UTC ISO-8601 with exactly millisecond precision. Never null. */
    fun format(instant: Instant): String = formatter.format(instant)

    /** Parses leniently; returns null on any malformed input. */
    fun parseOrNull(text: String?): Instant? {
        if (text.isNullOrEmpty()) return null
        return try {
            Instant.parse(text)
        } catch (_: DateTimeParseException) {
            try {
                java.time.OffsetDateTime.parse(text).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}

/**
 * Strict boolean parse for a JSON primitive: only the literal `"true"` is true.
 *
 * The boolean fields on [com.lumovault.lumovault.features.metadata.domain.model.PartitionItem]
 * are sparse — they are written only when true — so anything else under that
 * key is corruption, and falling back to the default rather than to a lenient
 * true keeps one bad byte from silently trashing an item's state.
 */
val kotlinx.serialization.json.JsonPrimitive.strictBooleanOrNull: Boolean?
    get() = when (content) {
        "true" -> true
        "false" -> false
        else -> null
    }

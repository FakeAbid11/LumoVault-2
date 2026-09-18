package com.lumovault.lumovault.features.metadata.domain.model

/**
 * Per-item transfer state. Serialized by [PartitionItem] as its ordinal index
 * (never by name) and deliberately excluded from the partition content hash —
 * a freshly-synced item must not re-dirty its partition just because its
 * transfer state changed. Parse sites clamp the index into range so one corrupt
 * byte can't take down a whole partition document.
 *
 * Ported from lib/features/media/domain/models/media_item.dart.
 */
enum class MediaStatus {
    pending,
    uploading,
    uploaded,
    failed,
    excluded;

    val index: Int get() = ordinal

    companion object {
        fun fromIndex(index: Int): MediaStatus {
            val clamped = index.coerceIn(0, entries.size - 1)
            return entries[clamped]
        }
    }
}

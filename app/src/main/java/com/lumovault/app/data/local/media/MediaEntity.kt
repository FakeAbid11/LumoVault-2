package com.lumovault.app.data.local.media

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One indexed media file. The columns are exactly the metadata PRD section 61 asks the local
 * index to carry — nothing speculative.
 *
 * Backup-related state deliberately does *not* live here. PRD section 61 models it as its own
 * record (hash, Telegram message id, upload state, verified time), so Phase 6 adds a second table
 * keyed by [mediaStoreId] instead of widening this one and forcing every row to be rewritten.
 */
@Entity(
    tableName = "media",
    indices = [
        // The timeline sorts by date and the prune step filters by scan id; those are the two
        // queries this table actually serves. No index on content_uri: it is derived from the
        // primary key, so a second index would only slow writes down.
        Index("date_added_seconds"),
        Index("media_type"),
    ],
)
data class MediaEntity(
    /**
     * MediaStore's own row id. Used as identity because it is stable for the life of the file and
     * unique across images and videos; display names are not (`IMG_0001.jpg` appears in many
     * folders), so they cannot key a row.
     */
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    @ColumnInfo(name = "content_uri")
    val contentUri: String,

    /** Stored as its stable [com.lumovault.app.domain.model.MediaType] key, not an ordinal. */
    @ColumnInfo(name = "media_type")
    val mediaType: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "display_name")
    val displayName: String,

    @ColumnInfo(name = "relative_path", defaultValue = "")
    val relativePath: String = "",

    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    @ColumnInfo(name = "date_added_seconds")
    val dateAddedSeconds: Long,

    @ColumnInfo(name = "date_modified_seconds")
    val dateModifiedSeconds: Long,

    @ColumnInfo(name = "width", defaultValue = "0")
    val width: Int = 0,

    @ColumnInfo(name = "height", defaultValue = "0")
    val height: Int = 0,

    /** Null for still images; MediaStore reports no duration for those. */
    @ColumnInfo(name = "duration_millis")
    val durationMillis: Long?,

    /**
     * Scan generation, written on every upsert. Rows left behind by the current scan are deleted
     * with one statement, which is why this exists instead of a `NOT IN (…ids…)` clause: a
     * ten-thousand-item library would exceed SQLite's parameter limit.
     */
    @ColumnInfo(name = "last_seen_scan_id", defaultValue = "0")
    val lastSeenScanId: Long = 0,
)

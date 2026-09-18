package com.lumovault.lumovault.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Upload state. [ordinal] is the persisted int value, matching the Flutter
 * schema (0=pending … 4=excluded).
 */
enum class MediaStatus { pending, uploading, uploaded, failed, excluded }

/**
 * One photo or video on the device.
 *
 * Column set mirrors the Flutter `MediaItems` drift table; [localId] is the
 * stable MediaStore id and the row identity used by album joins.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["file_hash"], name = "idx_media_items_file_hash"),
        Index(value = ["status"], name = "idx_media_items_status"),
        Index(value = ["album_name"], name = "idx_media_items_album_name"),
        Index(value = ["created_at"], name = "idx_media_items_created_at"),
        Index(value = ["is_favorite"], name = "idx_media_items_is_favorite"),
        Index(value = ["is_trashed", "trashed_at"], name = "idx_media_items_trashed_trashed_at"),
        // local_id is the row identity referenced by album_items.media_id;
        // SQLite requires a unique index on any FK-referenced column.
        Index(value = ["local_id"], unique = true, name = "idx_media_items_local_id"),
    ],
)
data class MediaItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "local_id") val localId: String,
    @ColumnInfo(name = "file_hash") val fileHash: String,
    @ColumnInfo(name = "telegram_message_id") val telegramMessageId: String? = null,
    @ColumnInfo(name = "telegram_file_id") val telegramFileId: String? = null,
    @ColumnInfo(name = "file_path") val filePath: String,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "file_size") val fileSize: Long,
    val width: Int,
    val height: Int,
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
    /** Epoch millis. */
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "modified_at") val modifiedAt: Long,
    @ColumnInfo(name = "scanned_at") val scannedAt: Long,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long? = null,
    @ColumnInfo(name = "backed_up_at") val backedUpAt: Long? = null,
    val status: MediaStatus = MediaStatus.pending,
    @ColumnInfo(name = "error_message") val errorMessage: String? = null,
    @ColumnInfo(name = "is_favorite") val isFavorite: Boolean = false,
    @ColumnInfo(name = "is_hidden") val isHidden: Boolean = false,
    @ColumnInfo(name = "is_archived") val isArchived: Boolean = false,
    @ColumnInfo(name = "is_trashed") val isTrashed: Boolean = false,
    @ColumnInfo(name = "trashed_at") val trashedAt: Long? = null,
    @ColumnInfo(name = "is_excluded") val isExcluded: Boolean = false,
    @ColumnInfo(name = "album_name") val albumName: String? = null,
    @ColumnInfo(name = "device_folder") val deviceFolder: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    @ColumnInfo(name = "ai_labels") val aiLabels: List<String> = emptyList(),
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "is_location_user_set") val isLocationUserSet: Boolean = false,
    @ColumnInfo(name = "is_date_user_set") val isDateUserSet: Boolean = false,
    @ColumnInfo(name = "location_name") val locationName: String? = null,
    @ColumnInfo(name = "clip_embedding") val clipEmbedding: List<Float>? = null,
)

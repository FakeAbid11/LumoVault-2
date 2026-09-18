package com.lumovault.lumovault.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-created album. [coverId] references MediaItems.local_id.
 */
@Entity(tableName = "albums")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    @ColumnInfo(name = "cover_id") val coverId: String? = null,
    val position: Int = 0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(
    tableName = "album_items",
    foreignKeys = [
        ForeignKey(entity = AlbumEntity::class, parentColumns = ["id"], childColumns = ["album_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = MediaItemEntity::class, parentColumns = ["local_id"], childColumns = ["media_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [
        Index(value = ["album_id", "media_id"], unique = true),
        // media_id lookups (albumsForMedia) can't use the composite index,
        // whose leading column is album_id.
        Index(value = ["media_id"], name = "idx_album_items_media_id"),
    ],
)
data class AlbumItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "album_id") val albumId: Long,
    /** MediaItems.local_id. */
    @ColumnInfo(name = "media_id") val mediaId: String,
    @ColumnInfo(name = "added_at") val addedAt: Long,
)

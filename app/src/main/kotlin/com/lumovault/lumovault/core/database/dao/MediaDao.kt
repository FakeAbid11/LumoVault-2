package com.lumovault.lumovault.core.database.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.MediaStatus
import kotlinx.coroutines.flow.Flow

/** A duplicate group: a content hash shared by 2+ items. */
data class DuplicateHash(
    @ColumnInfo(name = "file_hash") val fileHash: String,
    val count: Int,
)

@Dao
interface MediaDao {

    // ------------------------------------------------------------------ reads

    /** Timeline: all non-trashed, non-hidden items, newest first. */
    @Query(
        """
        SELECT * FROM media_items
        WHERE is_trashed = 0 AND is_hidden = 0
        ORDER BY created_at DESC
        """,
    )
    fun timelineFlow(): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE is_trashed = 0 AND is_hidden = 0
        ORDER BY created_at DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun timelinePage(limit: Int, offset: Int): List<MediaItemEntity>

    /** Items in a device folder / album, newest first. */
    @Query(
        """
        SELECT * FROM media_items
        WHERE album_name = :albumName AND is_trashed = 0 AND is_hidden = 0
        ORDER BY created_at DESC
        """,
    )
    fun byAlbumFlow(albumName: String): Flow<List<MediaItemEntity>>

    @Query(
        """
        SELECT * FROM media_items
        WHERE is_favorite = 1 AND is_trashed = 0 AND is_hidden = 0
        ORDER BY created_at DESC
        """,
    )
    fun favoritesFlow(): Flow<List<MediaItemEntity>>

    @Query("SELECT * FROM media_items WHERE is_trashed = 1 ORDER BY trashed_at DESC")
    fun trashedFlow(): Flow<List<MediaItemEntity>>

    /** Case-insensitive search over file name and description. */
    @Query(
        """
        SELECT * FROM media_items
        WHERE is_trashed = 0 AND is_hidden = 0
          AND (LOWER(file_name) LIKE '%' || LOWER(:query) || '%'
            OR LOWER(description) LIKE '%' || LOWER(:query) || '%')
        ORDER BY created_at DESC
        """,
    )
    suspend fun search(query: String): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE local_id = :localId LIMIT 1")
    suspend fun byLocalId(localId: String): MediaItemEntity?

    /** Batch lookup keyed by localId — used to carry existing PKs forward
     * onto freshly scanned items before an upsert, so the conflict targets
     * the right row instead of colliding on the localId unique index. */
    @Query("SELECT * FROM media_items WHERE local_id IN (:localIds)")
    suspend fun byLocalIds(localIds: List<String>): List<MediaItemEntity>

    @Query("SELECT * FROM media_items WHERE file_hash = :fileHash LIMIT 1")
    suspend fun byHash(fileHash: String): MediaItemEntity?

    @Query(
        """
        SELECT * FROM media_items
        WHERE file_hash = :fileHash AND is_trashed = 0
        ORDER BY created_at DESC
        """,
    )
    suspend fun byFileHash(fileHash: String): List<MediaItemEntity>

    @Query(
        """
        SELECT file_hash, COUNT(*) AS count FROM media_items
        WHERE file_hash != '' AND is_trashed = 0
        GROUP BY file_hash HAVING count >= 2
        """,
    )
    suspend fun duplicateHashes(): List<DuplicateHash>

    /** Distinct album / device-folder names present in the library. */
    @Query(
        """
        SELECT DISTINCT album_name FROM media_items
        WHERE album_name IS NOT NULL AND is_trashed = 0
        """,
    )
    suspend fun albumNames(): List<String>

    /** All rows, newest first. */
    @Query("SELECT * FROM media_items ORDER BY created_at DESC")
    suspend fun all(): List<MediaItemEntity>

    // --------------------------------------------------------------- writes

    @Upsert
    suspend fun upsert(item: MediaItemEntity): Long

    @Upsert
    suspend fun upsertAll(items: List<MediaItemEntity>)

    /** Replace the entire table in one transaction (full rescan semantics). */
    @Transaction
    suspend fun replaceAll(items: List<MediaItemEntity>) {
        deleteAll()
        insertAll(items)
    }

    @Query("DELETE FROM media_items")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Query("DELETE FROM media_items WHERE local_id IN (:localIds)")
    suspend fun deleteByLocalIds(localIds: List<String>)

    // --------------------------------------------------- partial updates

    @Query("UPDATE media_items SET is_favorite = :favorite WHERE local_id = :localId")
    suspend fun setFavorite(localId: String, favorite: Boolean)

    @Query("UPDATE media_items SET is_hidden = :hidden WHERE local_id = :localId")
    suspend fun setHidden(localId: String, hidden: Boolean)

    @Query("UPDATE media_items SET is_archived = :archived WHERE local_id = :localId")
    suspend fun setArchived(localId: String, archived: Boolean)

    @Query("UPDATE media_items SET is_trashed = 1, trashed_at = :trashedAt WHERE local_id = :localId")
    suspend fun moveToTrash(localId: String, trashedAt: Long)

    @Query("UPDATE media_items SET is_trashed = 0, trashed_at = NULL WHERE local_id = :localId")
    suspend fun restoreFromTrash(localId: String)

    @Query("UPDATE media_items SET is_excluded = :excluded WHERE local_id = :localId")
    suspend fun setExcluded(localId: String, excluded: Boolean)

    @Query("UPDATE media_items SET status = :status, error_message = :error WHERE local_id = :localId")
    suspend fun setStatus(localId: String, status: MediaStatus, error: String?)

    @Query(
        """
        UPDATE media_items
        SET status = :status, telegram_message_id = :messageId, telegram_file_id = :fileId,
            uploaded_at = :uploadedAt, backed_up_at = :backedUpAt, error_message = NULL
        WHERE local_id = :localId
        """,
    )
    suspend fun markUploaded(
        localId: String,
        status: MediaStatus,
        messageId: String?,
        fileId: String?,
        uploadedAt: Long,
        backedUpAt: Long?,
    )

    @Query("UPDATE media_items SET thumbnail_path = :path WHERE local_id = :localId")
    suspend fun setThumbnailPath(localId: String, path: String?)

    @Query("UPDATE media_items SET description = :description WHERE local_id = :localId")
    suspend fun setDescription(localId: String, description: String?)

    @Query("UPDATE media_items SET tags = :tags WHERE local_id = :localId")
    suspend fun setTags(localId: String, tags: List<String>)

    @Query(
        """
        UPDATE media_items
        SET created_at = :createdAt, is_date_user_set = 1
        WHERE local_id = :localId
        """,
    )
    suspend fun setCaptureDate(localId: String, createdAt: Long)

    @Query(
        """
        UPDATE media_items
        SET latitude = :lat, longitude = :lng, location_name = :name, is_location_user_set = 1
        WHERE local_id = :localId
        """,
    )
    suspend fun setLocation(localId: String, lat: Double?, lng: Double?, name: String?)

    @Query("UPDATE media_items SET clip_embedding = :embedding WHERE local_id = :localId")
    suspend fun setClipEmbedding(localId: String, embedding: List<Float>?)

    @Query("UPDATE media_items SET ai_labels = :labels WHERE local_id = :localId")
    suspend fun setAiLabels(localId: String, labels: List<String>)
}

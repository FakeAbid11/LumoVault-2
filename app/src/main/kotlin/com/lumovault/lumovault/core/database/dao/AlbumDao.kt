package com.lumovault.lumovault.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.lumovault.lumovault.core.database.entity.AlbumEntity
import com.lumovault.lumovault.core.database.entity.AlbumItemEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AlbumDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun createAlbum(album: AlbumEntity): Long

    @Query("UPDATE albums SET name = :newName, updated_at = :now WHERE id = :albumId")
    suspend fun renameAlbum(albumId: Long, newName: String, now: Long)

    @Query("DELETE FROM album_items WHERE album_id = :albumId")
    suspend fun deleteAlbumItems(albumId: Long)

    @Query("DELETE FROM albums WHERE id = :albumId")
    suspend fun deleteAlbum(albumId: Long)

    @Query("UPDATE albums SET position = :newPosition, updated_at = :now WHERE id = :albumId")
    suspend fun reorderAlbum(albumId: Long, newPosition: Int, now: Long)

    @Query("UPDATE albums SET cover_id = :mediaId, updated_at = :now WHERE id = :albumId")
    suspend fun setCover(albumId: Long, mediaId: String?, now: Long)

    @Query("SELECT * FROM albums ORDER BY position ASC")
    fun allAlbumsFlow(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM albums ORDER BY position ASC")
    suspend fun allAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM albums WHERE id = :albumId LIMIT 1")
    suspend fun albumById(albumId: Long): AlbumEntity?

    @Query("SELECT COUNT(*) FROM album_items WHERE album_id = :albumId")
    suspend fun albumItemCount(albumId: Long): Int

    @Query("SELECT album_id AS albumId, COUNT(*) AS count FROM album_items GROUP BY album_id")
    suspend fun allAlbumCounts(): List<AlbumCount>

    @Query("SELECT media_id FROM album_items WHERE album_id = :albumId")
    suspend fun albumMediaIds(albumId: Long): List<String>

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM album_items WHERE album_id = :albumId AND media_id = :mediaId
        )
        """,
    )
    suspend fun isInAlbum(albumId: Long, mediaId: String): Boolean

    @Query("SELECT album_id FROM album_items WHERE media_id = :mediaId")
    suspend fun albumsForMedia(mediaId: String): List<Long>

    @Query(
        """
        SELECT media_items.* FROM media_items
        INNER JOIN album_items ON album_items.media_id = media_items.local_id
        WHERE album_items.album_id = :albumId
        ORDER BY album_items.added_at DESC
        """,
    )
    suspend fun itemsForAlbum(albumId: Long): List<MediaItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToAlbum(item: AlbumItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addToAlbumBatch(items: List<AlbumItemEntity>)

    @Query("DELETE FROM album_items WHERE album_id = :albumId AND media_id = :mediaId")
    suspend fun removeFromAlbum(albumId: Long, mediaId: String)

    @Query("DELETE FROM album_items WHERE media_id IN (:mediaIds)")
    suspend fun detachMediaFromAlbumItems(mediaIds: List<String>)

    /**
     * Set the cover to this album's own newest item; null when empty.
     *
     * The cover must come from THIS album — an earlier version had no album
     * predicate, so every mutation set every album's cover to the globally
     * newest photo in the library.
     */
    @Query(
        """
        UPDATE albums
        SET cover_id = (
                SELECT media_items.local_id FROM media_items
                INNER JOIN album_items ON album_items.media_id = media_items.local_id
                WHERE album_items.album_id = :albumId
                ORDER BY media_items.created_at DESC
                LIMIT 1
            ),
            updated_at = :now
        WHERE id = :albumId
        """,
    )
    suspend fun updateAutoCover(albumId: Long, now: Long)

    /**
     * Remove membership rows for deleted media and repair covers that pointed
     * at any of them. Without this, permanently deleting media left dangling
     * album_items rows (inflating counts) and stale cover pointers.
     */
    @Transaction
    suspend fun detachMediaFromAlbums(mediaIds: List<String>) {
        if (mediaIds.isEmpty()) return
        detachMediaFromAlbumItems(mediaIds)
        val now = System.currentTimeMillis()
        for (album in allAlbums()) {
            val cover = album.coverId
            if (cover != null && mediaIds.contains(cover)) {
                updateAutoCover(album.id, now)
            }
        }
    }
}

data class AlbumCount(
    val albumId: Long,
    val count: Int,
)

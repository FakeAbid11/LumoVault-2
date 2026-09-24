package com.lumovault.app.data.local.media

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {
    @Query("SELECT * FROM media ORDER BY date_added_seconds DESC, media_store_id DESC LIMIT :limit")
    fun observeWindow(limit: Int): Flow<List<MediaEntity>>

    @Query("SELECT COUNT(*) FROM media")
    fun observeCount(): Flow<Int>

    @Query("SELECT media_type AS mediaType, COUNT(*) AS itemCount FROM media GROUP BY media_type")
    fun observeTypeCounts(): Flow<List<MediaTypeCount>>

    @Query("SELECT DISTINCT relative_path FROM media WHERE relative_path <> '' ORDER BY relative_path")
    fun observeFolders(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun currentCount(): Int

    /**
     * Written in one transaction per chunk by the repository: a scan can produce tens of thousands
     * of rows, and one statement each would mean tens of thousands of fsyncs.
     */
    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    /** Removes rows the current scan did not see. Returns how many went. */
    @Query("DELETE FROM media WHERE last_seen_scan_id < :scanId")
    suspend fun pruneBefore(scanId: Long): Int

    @Query("DELETE FROM media")
    suspend fun clear()

    /**
     * Which of [names] exist on this device at exactly [sizes]' byte count, as pairs.
     *
     * Used to label a cloud item *local + cloud* rather than *cloud-only*. Name alone is not enough —
     * `IMG_0001.jpg` is ordinary in three folders — and a hash would mean reading originals, which
     * Phase 4 must not do, so name and size together are the strongest identity available without
     * touching file bytes.
     */
    @Query(
        """
        SELECT display_name AS displayName, size_bytes AS sizeBytes FROM media
        WHERE display_name IN (:names)
        """,
    )
    suspend fun findByName(names: List<String>): List<LocalNameMatch>
}

data class LocalNameMatch(
    val displayName: String,
    val sizeBytes: Long,
)

data class MediaTypeCount(
    val mediaType: String,
    val itemCount: Int,
)

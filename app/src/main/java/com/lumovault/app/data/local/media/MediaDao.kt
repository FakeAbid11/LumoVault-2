package com.lumovault.app.data.local.media

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The media index.
 *
 * Four of these reads carry `WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0`.
 * That is the whole of PRD section 9's "archived and trashed items are hidden from the timeline", and it
 * lives here, in the query, rather than in a filter the UI applies afterwards: a window is a `LIMIT` over
 * what is *visible*, so hiding items in Kotlin would quietly shorten every page while `observeCount`
 * still reported the unfiltered total — the screen would decide it had loaded everything when it had not.
 * The `LEFT JOIN` with `COALESCE` is what keeps an item the user never organised (no row at all) visible,
 * which is the overwhelming majority of a library.
 */
@Dao
interface MediaDao {
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC LIMIT :limit
        """,
    )
    fun observeWindow(limit: Int): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT COUNT(*) FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    fun observeCount(): Flow<Int>

    @Query(
        """
        SELECT m.media_type AS mediaType, COUNT(*) AS itemCount FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0
        GROUP BY m.media_type
        """,
    )
    fun observeTypeCounts(): Flow<List<MediaTypeCount>>

    @Query("SELECT DISTINCT relative_path FROM media WHERE relative_path <> '' ORDER BY relative_path")
    fun observeFolders(): Flow<List<String>>

    @Query(
        """
        SELECT COUNT(*) FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    suspend fun currentCount(): Int

    /**
     * One item by its MediaStore id, or null when the index does not hold it.
     *
     * The lookup a restore and a deletion both have to make: the id is known exactly (MediaStore's own row
     * id, which is what this table keys on), and the question is whether the scanner has caught up with it
     * yet. Guessing from a filename instead would match the second copy of `IMG_0001.jpg` that every camera
     * eventually produces.
     */
    @Query("SELECT * FROM media WHERE media_store_id = :id LIMIT 1")
    suspend fun rowFor(id: Long): MediaEntity?

    /**
     * Written in one transaction per chunk by the repository: a scan can produce tens of thousands
     * of rows, and one statement each would mean tens of thousands of fsyncs.
     */
    @Upsert
    suspend fun upsertAll(items: List<MediaEntity>)

    /** Removes rows the current scan did not see. Returns how many went. */
    @Query("DELETE FROM media WHERE last_seen_scan_id < :scanId")
    suspend fun pruneBefore(scanId: Long): Int

    /**
     * Removes specific items from the index, for the one case where the file itself is known to be gone:
     * Android confirmed a deletion LumoVault asked for.
     *
     * A scan's prune cannot do this job, because the MediaStore row can outlive the confirmation by
     * however long the next scan takes — and an item that is deleted and still indexed is an item the
     * library will offer to back up again.
     */
    @Query("DELETE FROM media WHERE media_store_id IN (:ids)")
    suspend fun deleteByIds(ids: Collection<Long>): Int

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

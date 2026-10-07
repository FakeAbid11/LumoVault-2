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

    /**
     * Every id the timeline can show, unwindowed — [observeWindow]'s set without its `LIMIT`.
     *
     * "Select all" asks for the whole library while the grid has only rendered its first pages, and
     * the count the strip then prints is the count the actions will act on — so the answer has to be
     * the same set the window query would return if nothing limited it: same joins, same `WHERE`,
     * ids only. Ordering is dropped because a selection is a set. The pair is asserted against real
     * SQLite in `SelectAllIdsRealSqlTest`.
     */
    @Query(
        """
        SELECT m.media_store_id FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE COALESCE(o.archived, 0) = 0 AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    suspend fun visibleIds(): List<Long>

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
     * The highest scan id any surviving row carries, or 0 on an empty index.
     *
     * The scan id is a clock reading and the prune is `last_seen_scan_id < :scanId`, so a scan's id has to
     * exceed every tag the previous scan left or the prune matches nothing. A clock that moves backwards (a
     * user changing the date) would mint a smaller id than the existing tags, no row would be `< scanId`, and
     * every file that left the device would stay in the index as a ghost. Read the current maximum and never
     * issue an id at or below it, and the tag stays monotonic across a clock change.
     */
    @Query("SELECT COALESCE(MAX(last_seen_scan_id), 0) FROM media")
    suspend fun maxScanId(): Long

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
     * Which of [names] exist on this device at that name's byte count, each with its row id.
     *
     * Used to label a cloud item *local + cloud* rather than *cloud-only*. Name alone is not enough —
     * `IMG_0001.jpg` is ordinary in three folders — and a hash would mean reading originals, which
     * Phase 4 must not do, so name and size together are the strongest identity available without
     * touching file bytes.
     */
    @Query(
        """
        SELECT media_store_id AS mediaId, display_name AS displayName, size_bytes AS sizeBytes FROM media
        WHERE display_name IN (:names)
        """,
    )
    suspend fun findByName(names: List<String>): List<LocalNameMatch>

    /**
     * Full rows for these ids, without the visibility filter. Order is *not* part of the answer —
     * an `IN (…)` list is walked through the index and comes back in key order — so a caller whose
     * own contract is a sequence has to re-sequence the rows itself.
     *
     * This is the Cloud screen's reach-through: an item in the channel whose copy is on this device
     * is addressed by *its* MediaStore id, and the question "which rows are these" must not also
     * answer "which of them may Photos show" — an archived or trashed local copy still exists and
     * the viewer a cloud tap opens must find it. Chunked by the caller against the query ceiling.
     */
    @Query("SELECT * FROM media WHERE media_store_id IN (:ids)")
    suspend fun rowsFor(ids: List<Long>): List<MediaEntity>

    /**
     * What a share sends for these ids: the receiver's address and the file's declared type, and
     * nothing else — a share sheet never needs a date or a dimension, and reading columns it will
     * not show is work the tap already waited long enough for.
     *
     * [contentUri] is MediaStore's own `content://` value, which is what makes it grantable and
     * readable by the receiving app; there is no path column in this answer to leak instead.
     */
    @Query(
        """
        SELECT content_uri AS uri, mime_type AS mimeType FROM media
        WHERE media_store_id IN (:ids)
        """,
    )
    suspend fun shareTargets(ids: List<Long>): List<ShareMediaRow>
}

data class LocalNameMatch(
    /** MediaStore's row id, so a name match can reach the row itself and not only its label. */
    val mediaId: Long,
    val displayName: String,
    val sizeBytes: Long,
)

/** The share projection: an address and a type, which is the whole of [MediaShare]'s contract. */
data class ShareMediaRow(
    val uri: String,
    val mimeType: String,
)

data class MediaTypeCount(
    val mediaType: String,
    val itemCount: Int,
)

package com.lumovault.app.data.local.organization

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import com.lumovault.app.data.local.media.MediaEntity
import kotlinx.coroutines.flow.Flow

/**
 * User albums and their membership.
 *
 * The list query carries the count and the cover rather than asking per album: eight albums would
 * otherwise be nine queries every time anything changed, and the Albums screen is exactly where a user
 * scrolls a list of them. Both subqueries read through `media`, so an album's count is the number of its
 * members still on the device — a photo deleted outside LumoVault leaves the count as soon as it leaves
 * the index, rather than sitting in an album as a broken thumbnail.
 */
@Dao
interface AlbumDao {
    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.created_at AS createdAt,
               (SELECT COUNT(*) FROM album_media am JOIN media m ON m.media_store_id = am.media_store_id
                   WHERE am.album_id = a.id
                     AND (SELECT COALESCE(o.trashed_at, 0) FROM media_organization o
                          WHERE o.media_store_id = am.media_store_id) = 0) AS itemCount,
               (SELECT m.content_uri FROM album_media am JOIN media m ON m.media_store_id = am.media_store_id
                   WHERE am.album_id = a.id
                     AND (SELECT COALESCE(o.trashed_at, 0) FROM media_organization o
                          WHERE o.media_store_id = am.media_store_id) = 0
                   ORDER BY m.date_added_seconds DESC, m.media_store_id DESC LIMIT 1) AS coverUri
        FROM albums a
        ORDER BY a.created_at DESC, a.id DESC
        """,
    )
    fun observeAlbums(): Flow<List<AlbumListRow>>

    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.created_at AS createdAt,
               (SELECT COUNT(*) FROM album_media am JOIN media m ON m.media_store_id = am.media_store_id
                   WHERE am.album_id = a.id
                     AND (SELECT COALESCE(o.trashed_at, 0) FROM media_organization o
                          WHERE o.media_store_id = am.media_store_id) = 0) AS itemCount,
               (SELECT m.content_uri FROM album_media am JOIN media m ON m.media_store_id = am.media_store_id
                   WHERE am.album_id = a.id
                     AND (SELECT COALESCE(o.trashed_at, 0) FROM media_organization o
                          WHERE o.media_store_id = am.media_store_id) = 0
                   ORDER BY m.date_added_seconds DESC, m.media_store_id DESC LIMIT 1) AS coverUri
        FROM albums a WHERE a.id = :id LIMIT 1
        """,
    )
    fun observeAlbum(id: Long): Flow<AlbumListRow?>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("SELECT * FROM albums WHERE id = :id LIMIT 1")
    suspend fun album(id: Long): AlbumEntity?

    @Query("SELECT id FROM albums ORDER BY created_at DESC, id DESC")
    suspend fun albumIds(): List<Long>

    /**
     * Renaming is the only edit an album gets, and it changes nothing else: the id is what membership
     * rows and every caller hold, so a rename cannot orphan anything.
     */
    @Query("UPDATE albums SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    /**
     * Deletes the album and, by cascade, its membership rows. Nothing in this statement touches `media`,
     * `media_organization` or `backup_queue`: an album is a way of looking at photos, so deleting one
     * does not delete, un-favourite or un-back-up a single item.
     */
    @Query("DELETE FROM albums WHERE id = :id")
    suspend fun delete(id: Long): Int

    /**
     * Adds members that are in the media index and are not already in this album.
     *
     * The `SELECT … FROM media` is what makes a bad id impossible rather than dangling: membership of a
     * file the scanner never indexed could never be shown, and `INSERT OR IGNORE` against the composite
     * primary key makes a double-add one row. Returns nothing because Room gives an `INSERT` no row
     * count — [countMembers] on either side is how the caller learns what was added.
     */
    @Query(
        """
        INSERT OR IGNORE INTO album_media (album_id, media_store_id, added_at)
        SELECT :albumId, m.media_store_id, :now FROM media m WHERE m.media_store_id IN (:ids)
        """,
    )
    suspend fun addMembers(albumId: Long, ids: Collection<Long>, now: Long)

    @Query("SELECT COUNT(*) FROM album_media WHERE album_id = :albumId")
    suspend fun countMembers(albumId: Long): Int

    /** How many memberships went away, which is what lets the UI report the ones that were there. */
    @Query("DELETE FROM album_media WHERE album_id = :albumId AND media_store_id IN (:ids)")
    suspend fun removeMembers(albumId: Long, ids: Collection<Long>): Int

    /**
     * Takes an item out of every album at once, which is what a confirmed local deletion needs: the file
     * is gone, and a membership pointing at a MediaStore id that no longer resolves would show as a
     * broken cell until the next sync's cleanup.
     */
    @Query("DELETE FROM album_media WHERE media_store_id IN (:ids)")
    suspend fun removeFromEveryAlbum(ids: Collection<Long>): Int

    /** The album's own items, newest first, still on the device and not in Trash. */
    @Query(
        """
        SELECT m.* FROM album_media am
        JOIN media m ON m.media_store_id = am.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE am.album_id = :albumId AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeContent(albumId: Long, limit: Int): Flow<List<MediaEntity>>

    /** Which of [ids] are in this album, bounded by the caller's window instead of the album's size. */
    @Query(
        """
        SELECT media_store_id FROM album_media
        WHERE album_id = :albumId AND media_store_id IN (:ids)
        """,
    )
    suspend fun membersWithin(albumId: Long, ids: Collection<Long>): List<Long>

    /** Which albums hold an item, so "Add to album" can show what it is already in. */
    @Query(
        """
        SELECT am.album_id FROM album_media am
        JOIN albums a ON a.id = am.album_id
        WHERE am.media_store_id = :mediaStoreId
        ORDER BY a.created_at DESC, a.id DESC
        """,
    )
    suspend fun albumsContaining(mediaStoreId: Long): List<Long>

    /**
     * Membership of a file that is no longer in the index is dropped by the same sweep that clears its
     * organisation, for the same reason: invisible but permanent otherwise.
     *
     * Note what this is *not* — the media table is replaced with `INSERT OR REPLACE` on every scan, so a
     * foreign key from here to `media` would fire its cascade on a routine re-index and empty every
     * album. This statement is the deliberate version of that cleanup: it runs once per sync, after the
     * scan has decided what exists.
     */
    @Query("DELETE FROM album_media WHERE media_store_id NOT IN (SELECT media_store_id FROM media)")
    suspend fun cleanupOrphanMemberships(): Int
}

/**
 * One row of the Albums list: the album, how many of its members are still on the device and not in
 * Trash, and the newest one's thumbnail. A null [coverUri] means an empty album, which draws a
 * placeholder rather than a guess.
 */
data class AlbumListRow(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val itemCount: Int,
    val coverUri: String?,
)

/**
 * How the user organised one item: favourite, archived, trashed.
 *
 * Every write is a single upsert of *one* column, which is what keeps the three independent: favouriting
 * a photo cannot disturb its archive state and archiving cannot disturb a pending backup, because no
 * statement here reads the other two and writes them back. None of them touches `backup_queue` either —
 * organizing is not a reason to upload anything.
 */
@Dao
interface MediaOrganizationDao {
    @Query(
        """
        INSERT INTO media_organization (media_store_id, favorite) VALUES (:id, :value)
        ON CONFLICT(media_store_id) DO UPDATE SET favorite = :value
        """,
    )
    suspend fun setFavorite(id: Long, value: Boolean)

    @Query(
        """
        INSERT INTO media_organization (media_store_id, archived) VALUES (:id, :value)
        ON CONFLICT(media_store_id) DO UPDATE SET archived = :value
        """,
    )
    suspend fun setArchived(id: Long, value: Boolean)

    /**
     * Moves an item to Trash, or restamps when it got there.
     *
     * One upsert again, so an item that was never organised gets a row and one that was keeps its
     * favourite and archive marks — restoring it later has to find them.
     */
    @Query(
        """
        INSERT INTO media_organization (media_store_id, trashed_at) VALUES (:id, :trashedAt)
        ON CONFLICT(media_store_id) DO UPDATE SET trashed_at = :trashedAt
        """,
    )
    suspend fun setTrashedAt(id: Long, trashedAt: Long)

    @Query("UPDATE media_organization SET trashed_at = 0 WHERE media_store_id = :id")
    suspend fun restore(id: Long): Int

    /**
     * Forgets the organisation of items whose file really has gone, after Android confirmed the deletion.
     *
     * Only the file's departure makes this right: a trashed item whose bytes are still on the device
     * must stay in Trash, and a list that simply removed the row would put the photo back in the
     * timeline as though the user had never asked to delete it.
     */
    @Query("DELETE FROM media_organization WHERE media_store_id IN (:ids)")
    suspend fun clearFor(ids: Collection<Long>): Int

    @Query("SELECT COUNT(*) FROM media_organization WHERE trashed_at > 0")
    suspend fun trashedCount(): Int

    @Query("SELECT media_store_id FROM media_organization WHERE trashed_at > 0")
    suspend fun trashedIds(): List<Long>

    /** The window's favourites, so a grid can mark them without a query per cell. */
    @Query("SELECT media_store_id FROM media_organization WHERE favorite = 1 AND media_store_id IN (:ids)")
    fun observeFavoritesIn(ids: Collection<Long>): Flow<List<Long>>

    /**
     * Drops organisation for ids the index no longer holds, which is what a scan's prune leaves behind
     * when a file is deleted outside LumoVault.
     *
     * Every read joins to `media`, so an orphan row was already invisible — but it would never stop
     * being invisible, and a table of ids for files that no longer exist is how a library slowly fills
     * up with decisions nobody can undo. A subquery rather than `NOT IN (:ids)`: the library can be
     * 100,000 rows, and SQLite's parameter limit is roughly 1,000.
     */
    @Query("DELETE FROM media_organization WHERE media_store_id NOT IN (SELECT media_store_id FROM media)")
    suspend fun cleanupOrphans(): Int
}

/**
 * The system albums, derived at query time from what the index already knows.
 *
 * Each content query reads through `media` and excludes what is in Trash, so an album's list and its
 * count can never disagree and a trashed item appears in exactly one place. Ordering is
 * `date_added_seconds` descending — the Photos timeline's own rule — rather than each album inventing
 * one.
 */
@Dao
interface SystemAlbumDao {
    /**
     * All eight counts in one statement. Eight queries would each re-open the same tables the moment the
     * Albums screen appeared, and this is the one read that refreshes whenever anything in the library
     * changes.
     *
     * The three organisation counts join to `media` on purpose: an item the device no longer has is not
     * a favourite, and a row left behind by a prune must not inflate a number the user can see.
     */
    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM media m
                LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE m.relative_path LIKE 'DCIM/Camera/%' AND COALESCE(o.trashed_at, 0) = 0) AS camera,
            (SELECT COUNT(*) FROM media m
                LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE m.relative_path LIKE '%Screenshots/%' AND COALESCE(o.trashed_at, 0) = 0) AS screenshots,
            (SELECT COUNT(*) FROM media m
                LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE m.relative_path LIKE 'Download/%' AND COALESCE(o.trashed_at, 0) = 0) AS downloads,
            (SELECT COUNT(*) FROM media m
                LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE m.media_type = 'video' AND COALESCE(o.trashed_at, 0) = 0) AS videos,
            (SELECT COUNT(*) FROM media m
                JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE o.favorite = 1 AND o.trashed_at = 0) AS favorites,
            (SELECT COUNT(*) FROM media m
                JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE o.archived = 1 AND o.trashed_at = 0) AS archived,
            (SELECT COUNT(*) FROM media m
                JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE o.trashed_at > 0) AS trashed,
            (SELECT COUNT(*) FROM media m
                LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
                WHERE m.date_added_seconds >= :recentSince AND COALESCE(o.trashed_at, 0) = 0) AS recentlyAdded
        """,
    )
    fun observeCounts(recentSince: Long): Flow<SystemAlbumCountsRow>

    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE o.favorite = 1 AND o.trashed_at = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeFavorites(limit: Int): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT m.* FROM media m
        JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE o.archived = 1 AND o.trashed_at = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeArchived(limit: Int): Flow<List<MediaEntity>>

    /** Newest in Trash first, because that is the item the user most likely came to put back. */
    @Query(
        """
        SELECT m.*, o.trashed_at AS trashedAt FROM media m
        JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE o.trashed_at > 0
        ORDER BY o.trashed_at DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeTrashed(limit: Int): Flow<List<TrashedMediaRow>>

    /** [pattern] is a complete `LIKE` expression built by [com.lumovault.app.domain.model.SystemAlbum]. */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE m.relative_path LIKE :pattern AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeByPath(pattern: String, limit: Int): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE m.media_type = :mediaType AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeByType(mediaType: String, limit: Int): Flow<List<MediaEntity>>

    /**
     * Newly *discovered* media, which is PRD section 23's point: a file that arrived on the device last
     * week belongs here whatever its own dates say.
     *
     * The cutoff is `date_added_seconds` — MediaStore's record of when the file entered the device, and
     * the only arrival timestamp this build has, since reading EXIF is Phase 8's. The timeline keeps
     * using the same column for its day grouping; what makes this view different is that it is bounded
     * to the window, and it says so in its own header.
     */
    @Query(
        """
        SELECT m.* FROM media m
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE m.date_added_seconds >= :sinceSeconds AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeRecentlyAdded(sinceSeconds: Long, limit: Int): Flow<List<MediaEntity>>
}

/** The eight system-album counts, as one row. */
data class SystemAlbumCountsRow(
    val camera: Int,
    val screenshots: Int,
    val downloads: Int,
    val videos: Int,
    val favorites: Int,
    val archived: Int,
    val trashed: Int,
    val recentlyAdded: Int,
)

/** A trashed item plus when it went to Trash, which is what the Trash screen orders and reads by. */
data class TrashedMediaRow(
    @Embedded val media: MediaEntity,
    val trashedAt: Long,
)

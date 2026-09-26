package com.lumovault.app.data.local.metadata

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes of [MediaMetadataEntity].
 *
 * The map's question is spatial and it is asked on every pan, so it is answered by one indexed range
 * statement over a join — never by loading the library and filtering it in Kotlin, which is the same
 * mistake as an album that counts its members one query at a time.
 */
@Dao
interface MediaMetadataDao {

    /** One item's EXIF, or null when nobody has read the file yet. */
    @Query("SELECT * FROM media_metadata WHERE media_store_id = :mediaStoreId LIMIT 1")
    fun observe(mediaStoreId: Long): Flow<MediaMetadataEntity?>

    @Query("SELECT * FROM media_metadata WHERE media_store_id = :mediaStoreId LIMIT 1")
    suspend fun metadata(mediaStoreId: Long): MediaMetadataEntity?

    /**
     * Replaces this item's metadata wholesale.
     *
     * `@Upsert` is `INSERT OR REPLACE`, which is correct here and nowhere else in this schema: this row is
     * written only by the extraction pass, so a rewrite *is* the update. Nothing the user owns lives in
     * these columns.
     */
    @Upsert
    suspend fun record(row: MediaMetadataEntity)

    /**
     * Photos whose bytes have never been read.
     *
     * Only `photo` rows are candidates. A GIF carries no EXIF at all and an MP4's location is not in an
     * EXIF block either, so offering them up would cost an open-and-learn-nothing per item, forever. The
     * anti-join is what makes the pass O(remaining) rather than O(library), and an item that was read and
     * yielded nothing still gets a row — see [record] — so it never comes back.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri FROM media m
        LEFT JOIN media_metadata x ON x.media_store_id = m.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE x.media_store_id IS NULL
          AND m.media_type = 'photo'
          AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    suspend fun extractionCandidates(limit: Int): List<MetadataCandidateRow>

    /** How many photos are still waiting to be read, for a progress line that is not a guess. */
    @Query(
        """
        SELECT COUNT(*) FROM media m
        LEFT JOIN media_metadata x ON x.media_store_id = m.media_store_id
        WHERE x.media_store_id IS NULL AND m.media_type = 'photo'
        """,
    )
    suspend fun pendingExtractionCount(): Int

    /**
     * Every positioned photo inside the rectangle, newest first.
     *
     * A latitude-first range scan over `index_media_metadata_latitude_longitude`, joined to `media` so a
     * marker carries the thumbnail and the capture date the preview needs — one statement, not a query per
     * point. Items in Trash are excluded because they are hidden from every view that reads the library;
     * archived ones are not, because hiding them is the timeline's job and the map is not the timeline.
     *
     * [limit] is not decoration. A pinch-out over a country can match tens of thousands of photos, and
     * drawing that many markers is both a frozen UI and the thing clustering exists to avoid.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri, m.media_type AS mediaType,
               m.display_name AS displayName, m.date_added_seconds AS dateAddedSeconds,
               m.date_taken_seconds AS dateTakenSeconds,
               x.latitude AS latitude, x.longitude AS longitude
        FROM media_metadata x
        JOIN media m ON m.media_store_id = x.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE x.latitude IS NOT NULL AND x.longitude IS NOT NULL
          AND x.latitude BETWEEN :minLatitude AND :maxLatitude
          AND x.longitude BETWEEN :minLongitude AND :maxLongitude
          AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY COALESCE(m.date_taken_seconds, m.date_added_seconds) DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    fun observeInBoundingBox(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
        limit: Int,
    ): Flow<List<MapPhotoRow>>

    /**
     * The same rows without a rectangle, for a cluster the user tapped.
     *
     * [ids] is bounded by the caller, and has to be: `IN (:ids)` binds one parameter per id, and SQLite
     * refuses a statement over its limit — a cluster of a thousand photos in a city is not hypothetical,
     * which is why the repository takes a fixed slice rather than handing this the whole bucket.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri, m.media_type AS mediaType,
               m.display_name AS displayName, m.date_added_seconds AS dateAddedSeconds,
               m.date_taken_seconds AS dateTakenSeconds,
               x.latitude AS latitude, x.longitude AS longitude
        FROM media_metadata x
        JOIN media m ON m.media_store_id = x.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE x.latitude IS NOT NULL AND x.longitude IS NOT NULL
          AND m.media_store_id IN (:ids)
          AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY COALESCE(m.date_taken_seconds, m.date_added_seconds) DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    suspend fun photosWithIds(ids: Collection<Long>, limit: Int): List<MapPhotoRow>

    /** How many positioned photos exist at all, which is the difference between an empty map and a library with no GPS in it. */
    @Query(
        """
        SELECT COUNT(*) FROM media_metadata x
        JOIN media m ON m.media_store_id = x.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE x.latitude IS NOT NULL AND x.longitude IS NOT NULL
          AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    fun observeLocatedCount(): Flow<Int>

    /**
     * The rectangle every placeable photo sits in, as one aggregate.
     *
     * The map needs this before it has a size to read a viewport from: "where should this map open" cannot
     * wait for the first pan, and it must not be answered by loading up to 2,000 rows to take their average.
     * `MIN`/`MAX` over the same two indexes the viewport query range-scans is one pass and four numbers.
     *
     * The range predicates are not redundant with `IS NOT NULL`. A camera can write a GPS block that reads
     * latitude 91, and a rectangle built from that has no north edge at all — the map would open framing a
     * place that does not exist. The reader refuses such pairs on the way in
     * ([com.lumovault.app.domain.metadata.ExifFacts.location]); this says the same thing about rows already in
     * the table, including any written before that check existed.
     *
     * Trash is excluded for the same reason the viewport query excludes it: a hidden photo is not a place the
     * map should be promising to show.
     */
    @Query(
        """
        SELECT MIN(x.latitude) AS minLatitude, MAX(x.latitude) AS maxLatitude,
               MIN(x.longitude) AS minLongitude, MAX(x.longitude) AS maxLongitude,
               COUNT(*) AS placedCount
        FROM media_metadata x
        JOIN media m ON m.media_store_id = x.media_store_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE x.latitude IS NOT NULL AND x.longitude IS NOT NULL
          AND x.latitude BETWEEN -90.0 AND 90.0
          AND x.longitude BETWEEN -180.0 AND 180.0
          AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    suspend fun locatedBounds(): LocatedBoundsRow?

    /**
     * One item's coordinates, for the viewer's "view on map", which must not load a rectangle to answer.
     *
     * The ranges are here rather than only in [MediaLocation]'s constructor because the caller of this cannot
     * afford an exception: a stored pair outside the possible range answers "no position", which is what the
     * button then says, instead of throwing from a click.
     */
    @Query(
        """
        SELECT latitude, longitude FROM media_metadata
        WHERE media_store_id = :mediaStoreId AND latitude IS NOT NULL AND longitude IS NOT NULL
          AND latitude BETWEEN -90.0 AND 90.0 AND longitude BETWEEN -180.0 AND 180.0
        LIMIT 1
        """,
    )
    suspend fun coordinatesFor(mediaStoreId: Long): PhotoCoordinates?

    /**
     * Drops every read that produced no position, which is how a newly-granted media-location permission
     * gets its photos onto the map.
     *
     * Android redacts GPS from an app that does not hold `ACCESS_MEDIA_LOCATION`, so a file read before the
     * user allows it legitimately reports "no coordinates" — and keeping that answer forever would leave
     * the map empty no matter what the user then permits. Clearing those rows makes them candidates again;
     * rows that *do* have a position are untouched, because a read that found a GPS fix needs no second
     * look.
     */
    @Query("DELETE FROM media_metadata WHERE latitude IS NULL")
    suspend fun discardUnlocatedReads(): Int

    /** Drops metadata for files confirmed gone. */
    @Query("DELETE FROM media_metadata WHERE media_store_id IN (:ids)")
    suspend fun clearFor(ids: Collection<Long>): Int

    /**
     * Drops metadata the index no longer has a file for.
     *
     * Written as a subquery for the same reason every other sweep here is: a library of 100,000 items
     * cannot be passed as parameters.
     */
    @Query("DELETE FROM media_metadata WHERE media_store_id NOT IN (SELECT media_store_id FROM media)")
    suspend fun cleanupOrphans(): Int
}

/** A marker, with the two things a preview needs besides its position. */
data class MapPhotoRow(
    val mediaStoreId: Long,
    val contentUri: String,
    val mediaType: String,
    val displayName: String,
    val dateAddedSeconds: Long,
    val dateTakenSeconds: Long?,
    val latitude: Double,
    val longitude: Double,
)

/** A photo the extraction pass has not opened yet, and the handle its bytes are read through. */
data class MetadataCandidateRow(val mediaStoreId: Long, val contentUri: String)

/** Just a position, for a query that only ever asks for one. */
data class PhotoCoordinates(val latitude: Double, val longitude: Double)

/**
 * The four numbers that frame a library, and how many photos contributed.
 *
 * The extremes are nullable because an aggregate over no rows answers null rather than nothing, and "the
 * query ran and there is nothing placed yet" is the sentence the map has to be able to tell apart from "these
 * are the edges".
 */
data class LocatedBoundsRow(
    val minLatitude: Double?,
    val maxLatitude: Double?,
    val minLongitude: Double?,
    val maxLongitude: Double?,
    val placedCount: Int,
)


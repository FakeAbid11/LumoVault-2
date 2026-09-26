package com.lumovault.app.domain.repository

import com.lumovault.app.domain.map.LocatedBounds
import com.lumovault.app.domain.metadata.MetadataCandidate
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MapPhoto
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaMetadata
import kotlinx.coroutines.flow.Flow

/**
 * The file's own story: where it was taken, which camera took it, and which positioned photos are on
 * screen right now.
 *
 * Read-facing for the UI, and just wide enough for the extraction pass to store what it found — one
 * repository, because both jobs are about one table and splitting them would put the row-to-model mapping
 * in two places.
 *
 * Nothing here reaches Telegram, and nothing here opens a file: reading bytes belongs to
 * [com.lumovault.app.domain.metadata.MediaContentMetadataReader], and this layer stores and answers. That
 * split is what lets the map draw from Room while the library is still being read, and lets a photo whose
 * file is momentarily unreadable be recorded as *attempted* rather than retried forever.
 */
interface MediaMetadataRepository {
    /** One item's metadata, or null when the file has not been read or has nothing to say. */
    fun observe(mediaStoreId: Long): Flow<MediaMetadata?>

    /** The positioned photos inside [bounds], newest capture first, at most [limit] of them. */
    fun observeMapPhotos(bounds: MapBounds, limit: Int): Flow<List<MapPhoto>>

    /** The photos named by [mediaStoreIds], for a cluster the user tapped. */
    suspend fun mapPhotos(mediaStoreIds: Collection<Long>, limit: Int): List<MapPhoto>

    /** How many positioned photos exist, which is how the map tells "nothing here" from "not read yet". */
    fun observeLocatedCount(): Flow<Int>

    /**
     * The rectangle that holds every positioned photo in the library, or null when there are none to place.
     *
     * What the map opens on. Reading it costs one aggregate, so the answer is available before the map widget
     * has been laid out — which is the only moment at which framing a map is free: after that, moving it is
     * the user's, not the app's.
     */
    suspend fun locatedBounds(): LocatedBounds?

    /** One photo's position, or null when it has none. The viewer's "view on map" acts on the answer. */
    suspend fun locationFor(mediaStoreId: Long): MediaLocation?

    /**
     * Records the outcome of reading one file, [metadata] being null when the read found nothing worth
     * storing.
     *
     * A row is written either way, and that is the point: "this file has no EXIF" and "this file could not
     * be read" are both answers, and without recording them the next pass would open the same ten thousand
     * unreadable files again forever.
     */
    suspend fun record(mediaStoreId: Long, metadata: MediaMetadata?, extractedAtSeconds: Long)

    /** Photos whose bytes have never been read, newest first, each with the handle its file is opened through. */
    suspend fun extractionCandidates(limit: Int): List<MetadataCandidate>

    /** How many are still waiting, for a progress line that is a count rather than a guess. */
    suspend fun pendingExtractionCount(): Int

    /**
     * Forgets every read that found no position, so those files are candidates again.
     *
     * Called when the media-location permission is granted and only then. Android redacts GPS from an app
     * that does not hold it, so a pass run before the user allowed it correctly recorded "no coordinates" —
     * and without this, the map would stay empty for that library forever however honestly each read had
     * been logged.
     */
    suspend fun discardUnlocatedReads(): Int

    /** Forgets metadata for files the device confirmed as deleted. */
    suspend fun forgetDeleted(mediaStoreIds: Collection<Long>): Int
}

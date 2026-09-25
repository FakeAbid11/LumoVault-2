package com.lumovault.app.data.local.metadata

import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.organization.OrganizationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `media_metadata`, in memory, mirroring the SQL the map and the extraction pass actually run.
 *
 * The two things worth having right are the candidate filter and the ordering. Candidates are *photos with
 * no row yet, that are not in Trash* — a fake that ignored the media-type test would let the pass claim it
 * reads GIFs and videos, and a fake that ignored the Trash test would keep opening files the user threw away.
 * Ordering is `COALESCE(date_taken, date_added)` descending, which is the one place in this app where a
 * capture date changes what a list looks like, so it is the thing a paraphrase would lose.
 */
class FakeMediaMetadataDao(private val store: OrganizationStore) : MediaMetadataDao {
    /** How many times the pass asked for candidates — the figure that proves a pass terminates. */
    var candidateQueries = 0
        private set

    override fun observe(mediaStoreId: Long): Flow<MediaMetadataEntity?> =
        store.tick.map { store.metadata[mediaStoreId] }

    override suspend fun metadata(mediaStoreId: Long): MediaMetadataEntity? = store.metadata[mediaStoreId]

    override suspend fun record(row: MediaMetadataEntity) {
        store.metadata[row.mediaStoreId] = row
        store.bump()
    }

    override suspend fun extractionCandidates(limit: Int): List<MetadataCandidateRow> {
        candidateQueries++
        return store.media.values
            .filter { it.mediaType == PHOTO_TYPE }
            .filterNot { store.metadata.containsKey(it.mediaStoreId) }
            .filter { trashedAt(it.mediaStoreId) == 0L }
            .sortedWith(
                compareByDescending<MediaEntity> { it.dateAddedSeconds }.thenByDescending { it.mediaStoreId },
            )
            .take(limit)
            .map { MetadataCandidateRow(it.mediaStoreId, it.contentUri) }
    }

    override suspend fun pendingExtractionCount(): Int = store.media.values.count {
        it.mediaType == PHOTO_TYPE && !store.metadata.containsKey(it.mediaStoreId)
    }

    override fun observeInBoundingBox(
        minLatitude: Double,
        maxLatitude: Double,
        minLongitude: Double,
        maxLongitude: Double,
        limit: Int,
    ): Flow<List<MapPhotoRow>> = store.tick.map {
        store.metadata.values
            .filter { row -> row.latitude != null && row.longitude != null }
            .filter { row -> row.latitude!! in minLatitude..maxLatitude }
            .filter { row -> row.longitude!! in minLongitude..maxLongitude }
            .mapNotNull { row -> store.media[row.mediaStoreId] }
            .filter { trashedAt(it.mediaStoreId) == 0L }
            .map { media -> media.toMapRow() }
            .sortedByCaptureTime()
            .take(limit)
    }

    override suspend fun photosWithIds(ids: Collection<Long>, limit: Int): List<MapPhotoRow> =
        store.metadata.values
            .filter { it.mediaStoreId in ids && it.latitude != null && it.longitude != null }
            .mapNotNull { row -> store.media[row.mediaStoreId] }
            .filter { trashedAt(it.mediaStoreId) == 0L }
            .map { media -> media.toMapRow() }
            .sortedByCaptureTime()
            .take(limit)

    override fun observeLocatedCount(): Flow<Int> = store.tick.map {
        store.metadata.values.count { row ->
            row.latitude != null && row.longitude != null &&
                store.media.containsKey(row.mediaStoreId) &&
                trashedAt(row.mediaStoreId) == 0L
        }
    }

    override suspend fun coordinatesFor(mediaStoreId: Long): PhotoCoordinates? =
        store.metadata[mediaStoreId]
            ?.takeIf { it.latitude != null && it.longitude != null }
            ?.let { PhotoCoordinates(it.latitude!!, it.longitude!!) }

    override suspend fun clearFor(ids: Collection<Long>): Int {
        val matching = ids.count { store.metadata.containsKey(it) }
        ids.forEach { store.metadata.remove(it) }
        store.bump()
        return matching
    }

    override suspend fun cleanupOrphans(): Int {
        val orphans = store.metadata.keys.filterNot { store.media.containsKey(it) }
        orphans.forEach { store.metadata.remove(it) }
        store.bump()
        return orphans.size
    }

    override suspend fun discardUnlocatedReads(): Int {
        val unlocated = store.metadata.values.filter { it.latitude == null }.map { it.mediaStoreId }
        unlocated.forEach { store.metadata.remove(it) }
        store.bump()
        return unlocated.size
    }

    private fun MediaEntity.toMapRow(): MapPhotoRow {
        val row = requireNotNull(store.metadata[mediaStoreId])
        return MapPhotoRow(
            mediaStoreId = mediaStoreId,
            contentUri = contentUri,
            mediaType = mediaType,
            displayName = displayName,
            dateAddedSeconds = dateAddedSeconds,
            dateTakenSeconds = dateTakenSeconds,
            latitude = requireNotNull(row.latitude),
            longitude = requireNotNull(row.longitude),
        )
    }

    private fun List<MapPhotoRow>.sortedByCaptureTime(): List<MapPhotoRow> = sortedWith(
        compareByDescending<MapPhotoRow> { it.dateTakenSeconds ?: it.dateAddedSeconds }
            .thenByDescending { it.mediaStoreId },
    )

    /** `COALESCE(o.trashed_at, 0)` — no organisation row at all is the same as not being in Trash. */
    private fun trashedAt(mediaStoreId: Long): Long = store.organization[mediaStoreId]?.trashedAt ?: 0L

    private companion object {
        const val PHOTO_TYPE = "photo"
    }
}

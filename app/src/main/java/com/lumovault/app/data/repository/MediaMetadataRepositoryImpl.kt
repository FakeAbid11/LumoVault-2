package com.lumovault.app.data.repository

import com.lumovault.app.data.local.MAX_IDS_PER_QUERY
import com.lumovault.app.data.local.metadata.MapPhotoRow
import com.lumovault.app.data.local.metadata.MediaMetadataDao
import com.lumovault.app.data.local.metadata.MediaMetadataEntity
import com.lumovault.app.domain.metadata.MetadataCandidate
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MapPhoto
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.MediaMetadataRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Metadata, from the table the extraction pass writes.
 *
 * Reads are bounded by a caller-supplied limit rather than by what the library holds, because the map asks
 * its question on every pan: an unbounded result over a zoomed-out view is a request to load a country's
 * worth of photos into a Compose list, and clustering exists precisely so that is never needed.
 */
class MediaMetadataRepositoryImpl(
    private val mediaMetadata: MediaMetadataDao,
) : MediaMetadataRepository {

    override fun observe(mediaStoreId: Long): Flow<MediaMetadata?> =
        mediaMetadata.observe(mediaStoreId).map { row -> row?.toMetadata() }

    override fun observeMapPhotos(bounds: MapBounds, limit: Int): Flow<List<MapPhoto>> =
        mediaMetadata.observeInBoundingBox(
            minLatitude = bounds.minLatitude,
            maxLatitude = bounds.maxLatitude,
            minLongitude = bounds.minLongitude,
            maxLongitude = bounds.maxLongitude,
            limit = limit,
        ).map { rows -> rows.map(MapPhotoRow::toMapPhoto) }

    override suspend fun mapPhotos(mediaStoreIds: Collection<Long>, limit: Int): List<MapPhoto> {
        // Bounded before the query, not after: `IN (:ids)` binds a parameter per id, and SQLite's limit is
        // in the low hundreds — a city cluster can name a thousand photos, so the slice is what keeps this
        // a query rather than a crash.
        if (mediaStoreIds.isEmpty()) return emptyList()
        val ids = mediaStoreIds.take(MAX_IDS_PER_QUERY)
        return mediaMetadata.photosWithIds(ids, limit).map(MapPhotoRow::toMapPhoto)
    }

    override fun observeLocatedCount(): Flow<Int> = mediaMetadata.observeLocatedCount()

    override suspend fun locationFor(mediaStoreId: Long): MediaLocation? =
        mediaMetadata.coordinatesFor(mediaStoreId)?.let { MediaLocation(it.latitude, it.longitude) }

    override suspend fun record(mediaStoreId: Long, metadata: MediaMetadata?, extractedAtSeconds: Long) {
        // A row either way, which is the difference between "not yet read" and "read, and this is all there
        // is". Without it the next pass reopens every file that has nothing to say.
        mediaMetadata.record(
            MediaMetadataEntity(
                mediaStoreId = mediaStoreId,
                latitude = metadata?.location?.latitude,
                longitude = metadata?.location?.longitude,
                altitudeMeters = metadata?.altitudeMeters,
                cameraMake = metadata?.cameraMake,
                cameraModel = metadata?.cameraModel,
                lensModel = metadata?.lensModel,
                focalLengthMm = metadata?.focalLengthMm,
                apertureF = metadata?.apertureF,
                isoSpeed = metadata?.isoSpeed,
                shutterSeconds = metadata?.shutterSeconds,
                extractedAt = extractedAtSeconds,
            ),
        )
    }

    override suspend fun extractionCandidates(limit: Int): List<MetadataCandidate> =
        mediaMetadata.extractionCandidates(limit).map { row ->
            MetadataCandidate(mediaStoreId = row.mediaStoreId, contentUri = row.contentUri)
        }

    override suspend fun pendingExtractionCount(): Int = mediaMetadata.pendingExtractionCount()

    override suspend fun forgetDeleted(mediaStoreIds: Collection<Long>): Int =
        if (mediaStoreIds.isEmpty()) 0 else mediaMetadata.clearFor(mediaStoreIds)

    override suspend fun discardUnlocatedReads(): Int = mediaMetadata.discardUnlocatedReads()

    private fun MediaMetadataEntity.toMetadata(): MediaMetadata = MediaMetadata(
        // Rebuilt rather than trusted: a row written by an older build, or by a provider that reported a
        // nonsensical pair, must not be able to put a marker somewhere impossible.
        location = latitude?.let { lat -> longitude?.takeIf { lon -> lat in -90.0..90.0 && lon in -180.0..180.0 }?.let { lon -> MediaLocation(lat, lon) } },
        altitudeMeters = altitudeMeters,
        cameraMake = cameraMake,
        cameraModel = cameraModel,
        lensModel = lensModel,
        focalLengthMm = focalLengthMm,
        apertureF = apertureF,
        isoSpeed = isoSpeed,
        shutterSeconds = shutterSeconds,
    )

    private companion object {
        /** Comfortably under SQLite's parameter ceiling, and far more than a preview strip can draw. */
    }
}

private fun MapPhotoRow.toMapPhoto(): MapPhoto = MapPhoto(
    mediaStoreId = mediaStoreId,
    contentUri = contentUri,
    type = MediaType.fromStorageKey(mediaType),
    displayName = displayName,
    latitude = latitude,
    longitude = longitude,
    dateTakenSeconds = dateTakenSeconds,
    dateAddedSeconds = dateAddedSeconds,
)

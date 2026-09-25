package com.lumovault.app.data.repository

import com.lumovault.app.data.local.metadata.FakeMediaMetadataDao
import com.lumovault.app.data.local.metadata.MediaMetadataEntity
import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.domain.model.MapBounds
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.domain.repository.MediaMetadataRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The map's reads, and the rule that a photo only appears on a map if it can be placed.
 *
 * The rectangle is the interesting part: it must exclude what has no coordinates, what is in Trash, and what
 * the index no longer has — and must *not* exclude what is merely archived, because hiding an item from the
 * timeline was Phase 7's decision for the timeline alone. Each of those is a clause in one SQL statement, so
 * each is asserted against a fake that repeats the clause rather than paraphrasing it.
 */
class MediaMetadataRepositoryTest {
    private val store = OrganizationStore()
    private val organization = FakeMediaOrganizationDao(store)
    private val metadata = FakeMediaMetadataDao(store)
    private val repository: MediaMetadataRepository = MediaMetadataRepositoryImpl(mediaMetadata = metadata)

    private val berlin = MapBounds(minLatitude = 52.0, maxLatitude = 53.0, minLongitude = 13.0, maxLongitude = 14.0)

    @Test
    fun onlyPositionedPhotosInsideTheRectangleComeBack() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L))
        position(1L, 52.52, 13.40)
        position(2L, 48.85, 2.35)
        position(3L, 52.53, 13.41)

        val inside = repository.observeMapPhotos(berlin, limit = 10).first()

        assertEquals("paris is not in berlin", listOf(3L, 1L), inside.map { it.mediaStoreId })
    }

    @Test
    fun theOrderIsTheCaptureTimeTheFileActuallyCarries() = runBlocking {
        // Arrival and capture disagree on purpose: the newest file on the device is the oldest shot, so
        // whichever way the query orders is visible in the answer.
        store.index(
            FakeMediaRow(1L, dateAddedSeconds = 300L, dateTakenSeconds = 1_900_000_000L),
            FakeMediaRow(2L, dateAddedSeconds = 200L, dateTakenSeconds = 1_800_000_000L),
            FakeMediaRow(3L, dateAddedSeconds = 100L, dateTakenSeconds = 1_850_000_000L),
        )
        listOf(1L, 2L, 3L).forEach { position(it, 52.5, 13.4) }

        val photos = repository.observeMapPhotos(berlin, limit = 10).first()

        assertEquals(
            "newest shot first, which is a different list from newest file first",
            listOf(1L, 3L, 2L),
            photos.map { it.mediaStoreId },
        )
    }

    @Test
    fun aFileWithNoCoordinatesIsAbsentFromEveryMapRead() = runBlocking {
        store.index(FakeMediaRow(1L))
        repository.record(1L, metadata = null, extractedAtSeconds = 5L)

        assertEquals(0, repository.observeMapPhotos(berlin, limit = 10).first().size)
        assertEquals(0, repository.observeLocatedCount().first())
        assertNull(repository.locationFor(1L))
        assertEquals(
            "and the read is still recorded as done, so it is not a candidate again",
            emptyList<Long>(),
            repository.extractionCandidates(10).map { it.mediaStoreId },
        )
    }

    @Test
    fun aTrashedPhotoLeavesTheMapAndAnArchivedOneDoesNot() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        position(1L, 52.5, 13.4)
        position(2L, 52.5, 13.4)
        organization.setTrashedAt(1L, 9L)
        organization.setArchived(2L, true)

        val photos = repository.observeMapPhotos(berlin, limit = 10).first()

        assertEquals("Trash hides an item everywhere", listOf(2L), photos.map { it.mediaStoreId })
        assertEquals(1, repository.observeLocatedCount().first())
    }

    @Test
    fun aPhotoTheIndexNoLongerHoldsIsNotAMarker() = runBlocking {
        store.index(FakeMediaRow(1L))
        position(1L, 52.5, 13.4)

        // A file deleted outside the app: the index row is gone, and the metadata outlives it until the
        // sweep runs at the end of the next scan.
        store.media.remove(1L)

        assertEquals(0, repository.observeMapPhotos(berlin, limit = 10).first().size)
        assertEquals(1, metadata.cleanupOrphans())
        assertEquals("and the sweep takes the orphan with it", 0, store.metadata.size)
    }

    @Test
    fun aRectangleIsBoundedRatherThanMaterialised() = runBlocking {
        List(50) { indexAPhoto(it.toLong()) }

        assertEquals(12, repository.observeMapPhotos(berlin, limit = 12).first().size)
        assertEquals(
            "the limit is about the screen, not about the library",
            50,
            repository.observeLocatedCount().first(),
        )
    }

    @Test
    fun tappingAClusterNeverBindsMoreIdsThanSqliteCanTake() = runBlocking {
        val ids = (1L..1_200L).toList()
        ids.forEach { indexAPhoto(it) }

        val photos = repository.mapPhotos(ids, limit = 200)

        assertEquals(
            "a city-sized cluster is a slice of previews, not 1,200 bound parameters",
            200,
            photos.size,
        )
    }

    @Test
    fun grantingMediaLocationMakesTheLocationlessReadsCandidatesAgain() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        // Both read before the grant, so Android handed over a redacted copy: one still named its camera,
        // the other said nothing at all.
        repository.record(1L, cameraOnly("Pixel 9"), extractedAtSeconds = 5L)
        repository.record(2L, null, extractedAtSeconds = 5L)

        assertEquals(0, repository.observeLocatedCount().first())
        assertEquals(2, store.metadata.size)

        assertEquals(2, repository.discardUnlocatedReads())

        assertEquals("both are worth opening again", 2, repository.extractionCandidates(10).size)
        assertEquals(0, store.metadata.size)
    }

    @Test
    fun aReadThatFoundAPositionIsNotDiscardedByTheGrant() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        position(1L, 52.5, 13.4)
        repository.record(2L, null, extractedAtSeconds = 5L)

        assertEquals(1, repository.discardUnlocatedReads())
        assertEquals(
            "a GPS fix needs no second look, and re-reading it would cost the file again for nothing",
            listOf(2L),
            repository.extractionCandidates(10).map { it.mediaStoreId },
        )
    }

    @Test
    fun forgettingADeletedFileTakesItsPositionWithIt() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        position(1L, 52.5, 13.4)
        position(2L, 52.6, 13.5)

        assertEquals(1, repository.forgetDeleted(listOf(1L)))
        assertEquals(listOf(2L), repository.observeMapPhotos(berlin, limit = 10).first().map { it.mediaStoreId })
        assertEquals("an empty selection asks for nothing", 0, repository.forgetDeleted(emptyList()))
    }

    @Test
    fun metadataReadBackKeepsTheCameraAndThePositionApart() = runBlocking {
        store.index(FakeMediaRow(1L))
        repository.record(
            1L,
            MediaMetadata(
                location = MediaLocation(52.5, 13.4),
                altitudeMeters = 34.0,
                cameraMake = "Apple",
                cameraModel = "iPhone 15 Pro",
                lensModel = null,
                focalLengthMm = 24.0,
                apertureF = 1.8,
                isoSpeed = 125,
                shutterSeconds = 0.004,
            ),
            extractedAtSeconds = 7L,
        )

        val read = requireNotNull(repository.observe(1L).first())

        assertEquals(MediaLocation(52.5, 13.4), read.location)
        assertEquals("iPhone 15 Pro", read.cameraLabel)
        assertEquals(34.0, requireNotNull(read.altitudeMeters), 0.0)
        assertEquals(0.004, requireNotNull(read.shutterSeconds), 0.0)
        assertEquals(7L, requireNotNull(store.metadata[1L]).extractedAt)
        assertEquals(MediaLocation(52.5, 13.4), requireNotNull(repository.locationFor(1L)))
    }

    @Test
    fun anImpossiblePairStoredByAnotherBuildDegradesToNoLocation() = runBlocking {
        store.index(FakeMediaRow(1L))
        metadata.record(
            MediaMetadataEntity(
                mediaStoreId = 1L,
                latitude = 120.0,
                longitude = 13.4,
                cameraModel = "Who knows",
                extractedAt = 4L,
            ),
        )

        val read = requireNotNull(repository.observe(1L).first())

        assertEquals("the camera data still survives", "Who knows", read.cameraModel)
        assertNull(
            "and the map does not get a marker at a latitude that does not exist",
            read.location,
        )
    }

    @Test
    fun aVideoIsNeverACandidateHoweverLongTheLibraryStays() = runBlocking {
        store.index(
            FakeMediaRow(1L, type = MediaType.Video),
            FakeMediaRow(2L, type = MediaType.Gif),
            FakeMediaRow(3L),
        )

        assertEquals(
            "a GIF carries no EXIF and a video's location is not in an EXIF block, so opening either " +
                "would cost a read that can only answer nothing",
            listOf(3L),
            repository.extractionCandidates(10).map { it.mediaStoreId },
        )
        assertEquals(1, repository.pendingExtractionCount())
    }

    private fun indexAPhoto(id: Long) {
        store.index(FakeMediaRow(id, dateAddedSeconds = id))
        position(id, 52.5, 13.4)
    }

    private fun position(id: Long, latitude: Double, longitude: Double) {
        store.metadata[id] = MediaMetadataEntity(
            mediaStoreId = id,
            latitude = latitude,
            longitude = longitude,
            extractedAt = 1L,
        )
        store.bump()
    }

    private fun cameraOnly(model: String) = MediaMetadata(
        location = null,
        altitudeMeters = null,
        cameraMake = null,
        cameraModel = model,
        lensModel = null,
        focalLengthMm = null,
        apertureF = null,
        isoSpeed = null,
        shutterSeconds = null,
    )
}

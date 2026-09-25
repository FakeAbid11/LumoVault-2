package com.lumovault.app.domain.usecase

import com.lumovault.app.data.local.metadata.FakeMediaMetadataDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.data.repository.MediaMetadataRepositoryImpl
import com.lumovault.app.domain.metadata.MediaContentMetadataReader
import com.lumovault.app.domain.metadata.MetadataCandidate
import com.lumovault.app.domain.metadata.MetadataFailure
import com.lumovault.app.domain.metadata.MetadataRead
import com.lumovault.app.domain.model.MediaLocation
import com.lumovault.app.domain.model.MediaMetadata
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bounded EXIF pass: what it promises to write, and when it promises to stop.
 *
 * The interesting assertions are about termination rather than about coordinates — those are tested where
 * they are produced, in [com.lumovault.app.domain.metadata.ExifFactsTest]. A pass over somebody's camera roll
 * has to end, has to resume from the right place, and must never record a file as answered when it was only
 * attempted. All three are decisions this class makes, and none of them is visible on a screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExtractMediaMetadataUseCaseTest {
    private val store = OrganizationStore()
    private val metadata = FakeMediaMetadataDao(store)
    private val repository = MediaMetadataRepositoryImpl(metadata = metadata)
    private val reader = FakeReader()

    private var clock = 1_790_000_000L
    private var millis = 0L

    private fun useCase(stageSize: Int = 12, budgetMillis: Long = 4_000L) = ExtractMediaMetadataUseCase(
        metadata = repository,
        reader = reader,
        nowSeconds = { clock },
        nowMillis = { millis },
        stageSize = stageSize,
        budgetMillis = budgetMillis,
    )

    @Test
    fun readsEveryCandidateAndCountsWhatItFound() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L))
        reader.answers[uri(1L)] = located(52.5, 13.4)
        reader.answers[uri(2L)] = MetadataRead.NothingRecorded
        reader.answers[uri(3L)] = MetadataRead.Unreadable(MetadataFailure.Unreadable)

        val run = useCase().run()

        assertEquals(3, run.read)
        assertEquals("the two that answered were written", 2, run.recorded)
        assertEquals(1, run.located)
        assertEquals(false, run.stoppedEarly)
        assertEquals(1_790_000_000L, requireNotNull(store.metadata[1L]).extractedAt)
        assertNull(
            "a file it could not open is not an answer about that file, so no row exists for it",
            store.metadata[3L],
        )
    }

    @Test
    fun aFileWithNothingToSayIsNeverOpenedAgain() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L))
        listOf(1L, 2L, 3L).forEach { reader.answers[uri(it)] = MetadataRead.NothingRecorded }

        val first = useCase().run()
        val second = useCase().run()

        assertEquals(3, first.read)
        assertEquals(
            "the whole point of recording a null: the second pass has nothing left to open",
            0,
            second.read,
        )
        assertEquals(0, repository.pendingExtractionCount())
    }

    @Test
    fun aPassCannotSpinForeverOnFilesItCannotOpen() = runBlocking {
        // Every candidate fails transiently, so nothing is recorded and all of them are still candidates.
        // Without a memory of what this run already tried, the stage query would hand the same files back and
        // the pass would never reach the ones behind them — so this asserts the query count, not just the
        // outcome, because a spinning pass also "finishes" if the budget is generous enough.
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L), FakeMediaRow(4L))
        listOf(1L, 2L, 3L, 4L).forEach {
            reader.answers[uri(it)] = MetadataRead.Unreadable(MetadataFailure.SourceMissing)
        }

        val run = useCase(stageSize = 1).run()

        assertEquals(4, run.read)
        assertEquals(0, run.recorded)
        assertTrue(
            "one query per file attempted, not one per round trip",
            metadata.candidateQueries <= 6,
        )
    }

    @Test
    fun theBudgetEndsAPassThatWouldOtherwiseGoOn() = runBlocking {
        // Ids are read newest-first, and each read costs a second on this clock, so a 2.5 s budget stops the
        // pass with one file still to open. Advancing a fake clock is the only way to test a budget at all
        // without the test itself waiting for it.
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L), FakeMediaRow(4L))
        listOf(1L, 2L, 3L, 4L).forEach {
            reader.answers[uri(it)] = MetadataRead.NothingRecorded
            reader.costs[uri(it)] = 1_000L
        }

        val run = useCase(stageSize = 4, budgetMillis = 2_500L).run()

        assertTrue("the pass noticed its budget", run.stoppedEarly)
        assertEquals(3, run.read)
        assertEquals(
            "and stopped with work left, which the next pass picks up",
            1,
            repository.pendingExtractionCount(),
        )
    }

    @Test
    fun twoPassesDoNotReadTheSameFiles() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        reader.answers[uri(1L)] = located(1.0, 2.0)
        reader.answers[uri(2L)] = located(3.0, 4.0)

        // The guard is one instance's state, because the container hands everybody the same pass — so the
        // second call has to be made on this same object to be a test of the guard rather than of nothing.
        val pass = useCase()
        var overlap: ExtractMediaMetadataUseCase.Run? = null
        reader.onFirstRead = { overlap = pass.run() }

        pass.run()
        reader.onFirstRead = null

        val second = requireNotNull(overlap)
        assertEquals("the overlap refused rather than doubling the reads", 0, second.read)
        assertEquals(true, second.stoppedEarly)
        assertEquals("and only one pass actually opened files", 2, reader.totalReads)
        assertEquals(2, store.metadata.size)
    }

    @Test
    fun aCancelledPassLeavesTheFilesItAlreadyReadRecorded() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L), FakeMediaRow(3L))
        listOf(1L, 2L, 3L).forEach { reader.answers[uri(it)] = located(1.0, 1.0) }
        reader.cancelsAfter = 3

        val job = launch { useCase(stageSize = 12).run() }
        job.join()

        assertTrue("the cancel propagates instead of being swallowed as a read failure", job.isCancelled)
        assertEquals(
            "and every answer it had already got is still in the database",
            2,
            store.metadata.size,
        )
    }

    @Test
    fun readingOneItemOnDemandWritesTheSameShapeOfRow() = runBlocking {
        store.index(FakeMediaRow(7L))
        reader.answers[uri(7L)] = located(48.85, 2.35)

        val outcome = useCase().readOne(MetadataCandidate(mediaStoreId = 7L, contentUri = uri(7L)))

        assertEquals(ExtractMediaMetadataUseCase.Outcome.Recorded(location = true), outcome)
        val row = requireNotNull(store.metadata[7L])
        assertEquals(MediaLocation(48.85, 2.35), MediaLocation(row.latitude!!, row.longitude!!))
    }

    private fun uri(id: Long) = "content://media/external/images/media/$id"

    private fun located(latitude: Double, longitude: Double) = MetadataRead.Found(
        MediaMetadata(
            location = MediaLocation(latitude, longitude),
            altitudeMeters = null,
            cameraMake = null,
            cameraModel = "Pixel 9",
            lensModel = null,
            focalLengthMm = null,
            apertureF = null,
            isoSpeed = null,
            shutterSeconds = null,
        ),
    )

    /**
     * Answers by uri, with a cost per file so a time budget can be tested without waiting for one, and a
     * self-cancelling read so cancellation can be made deterministic rather than raced.
     */
    private inner class FakeReader : MediaContentMetadataReader {
        val answers = mutableMapOf<String, MetadataRead>()
        val costs = mutableMapOf<String, Long>()
        var cancelsAfter = Int.MAX_VALUE
        var totalReads = 0
        var onFirstRead: (suspend () -> Unit)? = null

        override suspend fun read(contentUri: String): MetadataRead {
            totalReads++
            if (totalReads == 1) onFirstRead?.invoke()
            millis += costs[contentUri] ?: 0L
            if (totalReads >= cancelsAfter) currentCoroutineContext()[Job]?.cancel()
            return answers[contentUri] ?: MetadataRead.NothingRecorded
        }
    }
}

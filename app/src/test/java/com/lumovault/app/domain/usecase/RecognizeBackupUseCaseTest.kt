package com.lumovault.app.domain.usecase

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.data.repository.BackupQueueRepositoryImpl
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.repository.RemoteBackup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Backup recognition: the layered check, duplicate detection, changed media and reinstall recovery.
 *
 * The collaborators are the real [com.lumovault.app.data.repository.BackupQueueRepositoryImpl] over an
 * in-memory DAO and a fake cloud index, because the properties worth testing here live in the
 * *combination*: that a hash is measured once and then trusted, that the same hash found in the channel
 * ends in a `BACKED_UP` row pointing at the right message, and that none of it happens to a row a worker
 * is mid-send on. Any one of those in isolation would still pass with the others broken.
 */
class RecognizeBackupUseCaseTest {
    private val clock = QueueClock()
    private val dao = FakeBackupQueueDao()
    private val queue = BackupQueueRepositoryImpl(dao, clock::now)
    private val cloud = FakeCloudIndexRepository()
    private val hasher = FakeMediaContentHasher()

    private fun recognizer(stage: Int = 20) = RecognizeBackupUseCase(
        queue = queue,
        cloud = cloud,
        hasher = hasher,
        // Frozen, so a pass loops until its frontier is empty: what is under test is the candidate set,
        // not how much wall clock a hashing budget consumes.
        nanoTime = { 0L },
        candidatesPerStage = stage,
    )

    @Test
    fun anItemTheChannelAlreadyHoldsBecomesBackedUpAgainstThatMessage() = runBlocking {
        dao.withMedia(1L)
        cloud.given(HASH_A, CHAT, 777L)
        cloud.unrecognized = 1
        hasher.digest = HASH_A

        val run = recognizer().run()

        assertEquals(1, run.adopted)
        assertEquals("a recognised item is not queued work", UploadState.BackedUp, stateOf(1L))
        assertEquals(
            "the association is stored, which is what stops a duplicate upload later",
            777L,
            dao.row(1L).messageId,
        )
        assertEquals(CHAT, dao.row(1L).chatId)
        assertEquals(HASH_A, dao.row(1L).contentHash)
    }

    @Test
    fun anItemTheChannelDoesNotHoldStaysEligibleAndNeverBackedUp() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        hasher.digest = HASH_A

        val run = recognizer().run()

        assertEquals(0, run.adopted)
        assertEquals(UploadState.NotBackedUp, stateOf(1L))
        assertEquals("eligible means no message attached", 0L, dao.row(1L).messageId)
        assertEquals(HASH_A, dao.row(1L).contentHash)
    }

    @Test
    fun anUnchangedFileIsNeverReadAgainByTheNextPass() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        hasher.digest = HASH_A

        recognizer().run()
        assertEquals(1, hasher.reads)

        cloud.unrecognized = 0
        val second = recognizer().run()

        assertEquals("a settled library costs no file reads", 1, hasher.reads)
        assertEquals(0, second.considered)
    }

    @Test
    fun nothingIsHashedWhileTheChannelHasNoManifestUnclaimed() = runBlocking {
        dao.withMedia(1L, 2L, 3L)
        cloud.unrecognized = 0

        val run = recognizer().run()

        assertEquals(0, run.considered)
        assertEquals(0, hasher.reads)
    }

    @Test
    fun aDifferentByteCountIsGroundsForReadingTheFileAgain() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        recognizer().run()
        assertEquals(1, hasher.reads)

        dao.changeMedia(1L, sizeBytes = 9_000L, modifiedSeconds = FakeBackupQueueDao.DEFAULT_MODIFIED)
        cloud.unrecognized = 1
        recognizer().run()

        assertEquals(2, hasher.reads)
    }

    @Test
    fun aTouchedTimestampIsGroundsForReadingTheFileEvenAtTheSameSize() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        recognizer().run()

        dao.touchMedia(1L, modifiedSeconds = 1_600_000_000L)
        cloud.unrecognized = 1
        val run = recognizer().run()

        assertEquals(
            "an edit that kept the byte count still has to be looked at",
            2,
            hasher.reads,
        )
        assertEquals(1, run.considered)
    }

    @Test
    fun aFileWhoseContentReplacedIsGivenANewIdentityAndLosesItsBackup() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        hasher.digest = HASH_A
        recognizer().run()

        queue.adoptRemote(1L, RemoteBackup(CHAT, 777L))
        assertEquals(UploadState.BackedUp, stateOf(1L))

        // The user edited the photo in place: same MediaStore row, different bytes.
        dao.touchMedia(1L, modifiedSeconds = 1_600_000_000L)
        cloud.unrecognized = 1
        hasher.digest = HASH_B

        val run = recognizer().run()

        assertEquals(1, run.revoked)
        assertEquals(HASH_B, dao.row(1L).contentHash)
        assertEquals(
            "the new content is not the one that was stored, so no ✓ may claim otherwise",
            UploadState.NotBackedUp,
            stateOf(1L),
        )
        assertEquals(0L, dao.row(1L).messageId)
        assertEquals(
            "the old backup is still in the channel — recognition detaches a record, it does not delete a photo",
            777L,
            cloud.remoteBackupFor(HASH_A)?.messageId,
        )
    }

    @Test
    fun anEmptyLocalDatabaseIsRebuiltFromTheRemoteManifests() = runBlocking {
        // The reinstall scenario: no records at all, a channel that holds everything, and the same files
        // on disk under new ids.
        dao.withMedia(1L, 2L, 3L)
        hasher.digestsBySource[uriOf(1L)] = HASH_A
        hasher.digestsBySource[uriOf(2L)] = HASH_B
        hasher.digestsBySource[uriOf(3L)] = "c".repeat(64)
        cloud.given(HASH_A, CHAT, 11L)
        cloud.given(HASH_B, CHAT, 12L)
        cloud.unrecognized = 2

        val run = recognizer().run()

        assertEquals(3, run.considered)
        assertEquals(2, run.adopted)
        assertEquals(UploadState.BackedUp, stateOf(1L))
        assertEquals(UploadState.BackedUp, stateOf(2L))
        assertEquals(
            "a file with no manifest match stays eligible rather than being guessed at",
            UploadState.NotBackedUp,
            stateOf(3L),
        )
        assertEquals("each item resolved to the message that carries its own content", 11L, dao.row(1L).messageId)
        assertEquals(12L, dao.row(2L).messageId)
    }

    @Test
    fun photosVideosAndGifsAreAllRecognisedTheSameWay() = runBlocking {
        dao.withMedia(1L)
        dao.withGif(2L)
        dao.withVideo(3L)
        cloud.given(HASH_A, CHAT, 21L)
        cloud.unrecognized = 3
        hasher.digest = HASH_A

        val run = recognizer().run()

        assertEquals("every media type is hashed from its own bytes, whatever MediaStore called it", 3, run.adopted)
        assertEquals(listOf(UploadState.BackedUp, UploadState.BackedUp, UploadState.BackedUp), listOf(stateOf(1L), stateOf(2L), stateOf(3L)))
        assertEquals(
            "and each resolved to the same message, which is what a duplicate looks like",
            listOf(21L, 21L, 21L),
            listOf(dao.row(1L).messageId, dao.row(2L).messageId, dao.row(3L).messageId),
        )
    }

    @Test
    fun aFrontierLargerThanOneStageIsWalkedToTheEndInOnePass() = runBlocking {
        (1L..12L).forEach { dao.withMedia(it) }
        cloud.unrecognized = 12
        hasher.digest = HASH_A

        val run = recognizer(stage = 5).run()

        assertEquals(
            "five at a time, three stages, and the record of every item is filled",
            12,
            run.considered,
        )
        assertEquals(12, run.hashed)
        assertEquals(12, hasher.reads)
        assertEquals("the frontier emptied, so there is nothing to come back for", false, run.stoppedEarly)
    }

    @Test
    fun recognitionYieldsTheMomentTheQueueHasWorkToDeliver() = runBlocking {
        dao.withMedia(1L, 2L)
        queue.enqueue(listOf(1L))
        cloud.unrecognized = 2

        val run = recognizer().run()

        assertEquals(
            "an upload the user asked for is never made to wait behind a scan",
            0,
            run.considered,
        )
        assertEquals(true, run.stoppedEarly)
        assertEquals(0, hasher.reads)
    }

    @Test
    fun anUnreadableFileIsNotCalledABackedUpItem() = runBlocking {
        dao.withMedia(1L)
        cloud.unrecognized = 1
        hasher.failNext(BackupFailureKind.SourceMissing)

        val run = recognizer().run()

        assertEquals(1, run.unreadable)
        assertEquals(0, run.adopted)
        assertEquals(UploadState.NotBackedUp, stateOf(1L))
        assertEquals("", dao.row(1L).contentHash)
        assertTrue("the attempt is recorded so the next pass does not repeat it", dao.row(1L).hashedAt > 0L)
    }

    @Test
    fun aScanRefusesToSettleARowWhoseUploadIsInFlight() = runBlocking {
        dao.withMedia(1L)
        queue.enqueue(listOf(1L))
        // The worker's own claim, applied out of band: nothing the scan writes may reach into a row that
        // is mid-send, because that send is the only thing allowed to say how it ended.
        dao.forceState(1L, UploadState.Uploading)

        cloud.given(HASH_A, CHAT, 555L)
        cloud.unrecognized = 1
        hasher.digest = HASH_A

        val run = recognizer().run()

        assertEquals(0, run.adopted)
        assertEquals("the send still owns its row", UploadState.Uploading, stateOf(1L))
        assertEquals(
            "but the identity it learned is still worth keeping",
            HASH_A,
            dao.row(1L).contentHash,
        )
    }

    @Test
    fun aFailedItemIsReidentifiedWithoutBeingPutBackInTheQueue() = runBlocking {
        dao.withMedia(1L)
        queue.enqueue(listOf(1L))
        // The worker refused it, and nothing was ever measured about it. Recognition's change-detection
        // frontier covers exactly this: the item has a record whose hash is missing.
        dao.forceState(1L, UploadState.Failed)
        cloud.unrecognized = 0
        hasher.digest = HASH_A

        val run = recognizer().run()

        assertEquals(1, run.hashed)
        assertEquals(HASH_A, dao.row(1L).contentHash)
        assertEquals(
            "recognition measures content; only the retry affordance decides to send it again",
            UploadState.Failed,
            stateOf(1L),
        )
    }

    private fun stateOf(id: Long): UploadState = UploadState.fromStorageKey(dao.row(id).state)

    /** The URI [FakeBackupQueueDao] builds for a photo row, which is what the fake hasher keys digests by. */
    private fun uriOf(id: Long): String = "content://media/external/images/media/$id"

    private companion object {
        const val CHAT = 55_000_000_000L
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
    }
}

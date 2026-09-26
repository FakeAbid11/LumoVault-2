package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.repository.RemoteBackup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue's policy, exercised without a database.
 *
 * [FakeBackupQueueDao] stores rows and applies the same state writes the SQL does, so what is under test
 * is the repository's decisions — which transition is legal, how many attempts an item gets, what counts
 * as in flight, and which write a guarded `WHERE` clause is allowed to refuse. Those decisions are what
 * makes a ✓ on a thumbnail mean something, and they would be unreachable from a unit test if they lived
 * only inside a query.
 */
class BackupQueueRepositoryTest {
    private val clock = QueueClock()
    private val dao = FakeBackupQueueDao()
    private val repository: BackupQueueRepository =
        BackupQueueRepositoryImpl(dao, clock::now, inTransaction = { it() }, attemptCap = 3)

    @Test
    fun enqueueAddsOnlyKnownItemsAndOnlyOnce() = runBlocking {
        dao.withMedia(1L, 2L)

        assertEquals(2, repository.enqueue(listOf(1L, 2L)))
        assertEquals("a second tap must not queue the same item twice", 0, repository.enqueue(listOf(1L, 2L)))
        assertEquals("an id the index does not know is not queueable", 0, repository.enqueue(listOf(99L)))
    }

    /**
     * The multi-select that reaches `enqueue` has no ceiling, and the SQLite bundled with API 29 stops at
     * 999 bound variables per statement — a "Back Up" tap on a thousand selected photos used to be a crash
     * rather than a queue. Chunking against `MAX_IDS_PER_QUERY` (400) is the project-wide rule, and the
     * fake records the largest batch any statement was asked to bind so the rule is proven here instead of
     * trusted.
     */
    @Test
    fun enqueueSplitsASelectionTooLargeForOneStatement() = runBlocking<Unit> {
        val ids = (1L..900L).toList()
        dao.withMedia(*ids.toLongArray())

        assertEquals(900, repository.enqueue(ids))
        assertEquals(
            "every id in every chunk ended up with a row",
            900,
            repository.observeSummary().first().queued,
        )
        assertTrue(
            "no single statement was asked to bind more ids than the cap allows",
            dao.largestIdBatch <= 400,
        )
    }

    /**
     * The rule the whole duplicate argument rests on, read from the queue's side.
     *
     * An item with a settled row is not work: not for a tap on "Back Up", and not for a worker looking for
     * something to send. Both of those go through this one table, so a row in `backed_up` that could still
     * be claimed would put a second copy of the same bytes into the user's channel — after a restore, after
     * a reinstall, and after any repeated tap.
     */
    @Test
    fun anItemThatIsAlreadyStoredIsNeitherEnqueuedNorClaimedAgain() = runBlocking {
        dao.withMedia(1L)
        check(
            repository.recordRestored(
                mediaStoreId = 1L,
                remote = RemoteBackup(chatId = 555L, messageId = 777L),
                identity = MediaIdentity(
                    contentHash = "a".repeat(64),
                    observedSizeBytes = FakeBackupQueueDao.DEFAULT_SIZE,
                    observedModifiedSeconds = FakeBackupQueueDao.DEFAULT_MODIFIED,
                ),
            ),
        )

        assertEquals("a settled item is not something to ask for twice", 0, repository.enqueue(listOf(1L)))
        assertNull("and there is nothing here for a worker to take", repository.claimNext(555L))
        assertEquals(UploadState.BackedUp, dao.row(1L).state.asState())
    }

    @Test
    fun claimTakesTheOldestWaitingItemAndRecordsWhereItIsGoing() = runBlocking {
        dao.withMedia(1L, 2L)
        clock.nowValue = 10
        repository.enqueue(listOf(2L))
        clock.nowValue = 20
        repository.enqueue(listOf(1L))

        val claimed = repository.claimNext(chatId = 777L)

        // Item 2 was queued first, so it goes first — queue order, not grid order.
        assertEquals(2L, claimed?.mediaStoreId)
        assertEquals(UploadState.Preparing, claimed?.state)
        assertEquals(777L, claimed?.telegramChatId)
        assertEquals(UploadState.Preparing, dao.row(2L).state.asState())
    }

    @Test
    fun anItemThatLeftTheMediaIndexIsFailedAndTheNextOneIsClaimedInstead() = runBlocking {
        dao.withMedia(1L, 2L)
        repository.enqueue(listOf(1L, 2L))
        dao.dropMedia(1L)

        val claimed = repository.claimNext(chatId = 5L)

        assertEquals("the vanished item was skipped, not sent", 2L, claimed?.mediaStoreId)
        assertEquals(UploadState.Failed, dao.row(1L).state.asState())
        assertEquals(BackupFailureKind.SourceMissing.name, dao.row(1L).failure)
    }

    @Test
    fun anEmptyQueueClaimsNothing() = runBlocking {
        assertNull(repository.claimNext(chatId = 5L))
    }

    @Test
    fun theSentStateCarriesTheChatAndTheMessageTheSendReported() = runBlocking {
        val claimed = requireNotNull(claimedRequest())
        repository.markUploading(claimed.mediaStoreId)
        repository.markBackedUp(claimed.mediaStoreId, chatId = 6L, messageId = 4242L)

        val row = dao.row(claimed.mediaStoreId)
        assertEquals(UploadState.BackedUp, row.state.asState())
        assertEquals("the row records where the bytes landed, not where they were meant to go", 6L, row.chatId)
        assertEquals(4242L, row.messageId)
        assertEquals("a finished row keeps no staging path", "", row.stagedPath)
    }

    @Test
    fun aSuccessWrittenFromTheWrongStateIsRefused() = runBlocking<Unit> {
        dao.withMedia(1L)
        repository.enqueue(listOf(1L))

        // A row still in line, with nothing in flight and nothing stored: this is the write that would put
        // a ✓ on a photo nothing sent. `preparing` is allowed to close on remote evidence, which is a
        // decision with a message id behind it — see [RecognizeBackupUseCaseTest]'s dedup cases — and an
        // unclaimed row has no such evidence and no owner to hold it.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.markBackedUp(1L, chatId = 5L, messageId = 1L) }
        }
        assertEquals(UploadState.Queued, dao.row(1L).state.asState())
    }

    @Test
    fun aRetryableFailureReQueuesWithAnotherAttemptCounted() = runBlocking {
        val claimed = requireNotNull(claimedRequest())

        repository.release(claimed, BackupFailure(BackupFailureKind.Network))

        val row = dao.row(claimed.mediaStoreId)
        assertEquals(UploadState.Queued, row.state.asState())
        assertEquals(1, row.attempts)
        assertEquals(BackupFailureKind.Network.name, row.failure)
    }

    @Test
    fun aRetryableFailureStopsBeingRetryableAtTheCap() = runBlocking {
        val claimed = requireNotNull(claimedRequest()).copy(attempts = 2)

        repository.release(claimed, BackupFailure(BackupFailureKind.RateLimited))

        assertEquals(
            "the third attempt is the last one the queue will spend",
            UploadState.Failed,
            dao.row(claimed.mediaStoreId).state.asState(),
        )
    }

    @Test
    fun aPermanentFailureFailsAtOnceInsteadOfSpendingAttempts() = runBlocking {
        val claimed = requireNotNull(claimedRequest())

        repository.release(claimed, BackupFailure(BackupFailureKind.SourceMissing))

        val row = dao.row(claimed.mediaStoreId)
        assertEquals(UploadState.Failed, row.state.asState())
        assertEquals("no point burning three attempts on a deleted file", 1, row.attempts)
    }

    @Test
    fun restartingPutsRowsLeftMidFlightBackInTheQueueRatherThanCallingThemFailures() = runBlocking {
        dao.withMedia(1L, 2L, 3L)
        repository.enqueue(listOf(1L, 2L, 3L))
        dao.forceState(1L, UploadState.Preparing)
        dao.forceState(2L, UploadState.Uploading)
        dao.forceState(3L, UploadState.BackedUp)

        assertEquals(2, repository.reconcileInterrupted())

        assertEquals(UploadState.Queued, dao.row(1L).state.asState())
        assertEquals(UploadState.Queued, dao.row(2L).state.asState())
        assertEquals("a finished item is never undone", UploadState.BackedUp, dao.row(3L).state.asState())
    }

    @Test
    fun cancellingWithdrawsOnlyWhatHasNotStarted() = runBlocking {
        dao.withMedia(1L, 2L)
        repository.enqueue(listOf(1L, 2L))
        dao.forceState(2L, UploadState.Uploading)

        assertEquals(1, repository.cancelQueued())
        assertEquals(UploadState.Cancelled, dao.row(1L).state.asState())
        assertEquals(
            "an in-flight send is not interrupted from here",
            UploadState.Uploading,
            dao.row(2L).state.asState(),
        )
    }

    @Test
    fun theSummaryCountsEachBucketFromOneQueryAndIgnoresWithdrawnRows() = runBlocking {
        dao.withMedia(1L, 2L, 3L, 4L, 5L)
        repository.enqueue(listOf(1L, 2L, 3L, 4L, 5L))
        dao.forceState(2L, UploadState.Uploading)
        dao.forceState(3L, UploadState.BackedUp)
        dao.forceState(4L, UploadState.Failed)
        dao.forceState(5L, UploadState.Cancelled)

        val summary = repository.observeSummary().first()

        assertEquals(1, summary.queued)
        assertEquals(1, summary.inFlight)
        assertEquals(1, summary.backedUp)
        assertEquals(1, summary.failed)
        assertEquals("a cancelled row is neither pending nor delivered", 4, summary.total)
        assertEquals(2, summary.pending)
    }

    @Test
    fun statesForAWindowComeBackKeyedByItem() = runBlocking {
        dao.withMedia(1L, 2L)
        repository.enqueue(listOf(1L))

        val states = repository.observeStatesFor(listOf(1L, 2L, 3L)).first()

        assertEquals(UploadState.Queued, states[1L])
        assertNull("an item with no row has no state; that is how the grid reads not-backed-up", states[2L])
        assertNull(states[3L])
    }

    // `runBlocking<Unit>` because assertThrows hands back the throwable it caught: with the inferred
    // Unit this method would return Throwable, and JUnit rejects a non-void @Test by refusing to run
    // the whole class rather than reporting this one.
    @Test
    fun aStateThisBuildCannotNameFailsClosedInsteadOfReQueueingItself() = runBlocking<Unit> {
        val claimed = requireNotNull(claimedRequest())
        dao.forceRawState(claimed.mediaStoreId, "somesuch")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.markUploading(claimed.mediaStoreId) }
        }
    }

    @Test
    fun recordingAnIdentityLeavesARowsStateAloneEvenWhileItIsUploading() = runBlocking {
        val claimed = requireNotNull(claimedRequest())
        repository.markUploading(claimed.mediaStoreId)

        repository.recordIdentity(claimed.mediaStoreId, identity(HASH_A))

        val row = dao.row(claimed.mediaStoreId)
        assertEquals(
            "a scan learning what a file is must not decide what has happened to it",
            UploadState.Uploading,
            row.state.asState(),
        )
        assertEquals(HASH_A, row.contentHash)
        assertEquals("the queue's own claim survives the scan", 5L, row.chatId)
    }

    @Test
    fun anUnreadableFileIsRecordedAsAttemptedAndCallsItselfNothing() = runBlocking {
        dao.withItem(1L, sizeBytes = 4096L, modifiedSeconds = 900L)

        repository.recordIdentity(1L, MediaIdentity.unreadable(4096L, 900L))

        val row = dao.row(1L)
        assertEquals("no hash means no claim of any kind", "", row.contentHash)
        assertEquals(UploadState.NotBackedUp, row.state.asState())
        assertEquals(1L, row.hashedAt)
        assertEquals(
            "an attempted hash is not retried while the file's own figures are unchanged",
            emptyList<Any>(),
            repository.identityCandidates(includeWholeLibrary = true, limit = 10),
        )
    }

    @Test
    fun tappingBackUpMovesARecognisedItemWithoutDiscardingItsHash() = runBlocking {
        dao.withItem(1L, sizeBytes = 4096L, modifiedSeconds = 900L)
        repository.recordIdentity(1L, identity(HASH_A, size = 4096L, modified = 900L))

        assertEquals(1, repository.enqueue(listOf(1L)))

        val row = dao.row(1L)
        assertEquals(UploadState.Queued, row.state.asState())
        assertEquals(HASH_A, row.contentHash)
        assertEquals(4096L, row.contentSizeBytes)
        assertEquals(
            "a second tap spends nothing it has already paid for",
            0,
            repository.enqueue(listOf(1L)),
        )
    }

    @Test
    fun adoptionRefusesToSettleARowThatIsMidSend() = runBlocking {
        val claimed = requireNotNull(claimedRequest())
        repository.markUploading(claimed.mediaStoreId)

        assertEquals(
            false,
            repository.adoptRemote(claimed.mediaStoreId, RemoteBackup(chatId = 9L, messageId = 99L)),
        )

        val row = dao.row(claimed.mediaStoreId)
        assertEquals(UploadState.Uploading, row.state.asState())
        assertEquals("the message id belongs to the send that is running", 0L, row.messageId)
    }

    @Test
    fun adoptionClosesAQueuedItemAgainstTheMessageThatAlreadyHoldsIt() = runBlocking {
        dao.withMedia(1L)
        repository.enqueue(listOf(1L))
        repository.recordIdentity(1L, identity(HASH_A))

        assertEquals(true, repository.adoptRemote(1L, RemoteBackup(chatId = 42L, messageId = 777L)))

        val row = dao.row(1L)
        assertEquals(UploadState.BackedUp, row.state.asState())
        assertEquals(42L, row.chatId)
        assertEquals(777L, row.messageId)
        assertEquals("an adopted item leaves the queue", 0, repository.observeSummary().first().queued)
    }

    @Test
    fun onlyACompletedBackupCanHaveItsAssociationTakenAway() = runBlocking {
        dao.withMedia(1L, 2L)
        repository.enqueue(listOf(1L))
        repository.recordIdentity(1L, identity(HASH_A))
        repository.adoptRemote(1L, RemoteBackup(chatId = 42L, messageId = 777L))
        repository.recordIdentity(2L, identity(HASH_B))
        repository.enqueue(listOf(2L))

        assertEquals(true, repository.revokeAssociation(1L))
        assertEquals(
            "queued work is the user's to cancel, not a scan's to demote",
            false,
            repository.revokeAssociation(2L),
        )

        val revoked = dao.row(1L)
        assertEquals(UploadState.NotBackedUp, revoked.state.asState())
        assertEquals("the association goes; the message in Telegram does not", 0L, revoked.messageId)
        assertEquals(0L, revoked.chatId)
        assertEquals("the identity just measured stays", HASH_A, revoked.contentHash)
        assertEquals(UploadState.Queued, dao.row(2L).state.asState())
    }

    @Test
    fun recognisedItemsAreNotCountedAsPendingAnything() = runBlocking {
        dao.withMedia(1L, 2L, 3L)
        repository.recordIdentity(1L, identity(HASH_A))
        repository.recordIdentity(2L, identity(HASH_B))
        repository.enqueue(listOf(3L))

        val summary = repository.observeSummary().first()

        assertEquals(1, summary.queued)
        assertEquals("two files identified and never asked about are not a queue", 1, summary.total)
    }

    @Test
    fun aClaimedRowCarriesTheIdentityItWasRecordedWith() = runBlocking {
        dao.withItem(1L, sizeBytes = 4096L, modifiedSeconds = 900L)
        repository.recordIdentity(1L, identity(HASH_A, size = 4096L, modified = 900L))
        repository.enqueue(listOf(1L))

        val claimed = requireNotNull(repository.claimNext(chatId = 5L))

        assertEquals(HASH_A, claimed.contentHash)
        assertEquals(4096L, claimed.contentSizeBytes)
        assertEquals(900L, claimed.contentModifiedSeconds)
        assertEquals("the fast check holds for an untouched file", true, claimed.identityIsCurrent)
    }

    @Test
    fun aRowWhoseFileGrewSinceItWasHashedIsNoLongerCurrent() = runBlocking {
        dao.withItem(1L, sizeBytes = 4096L, modifiedSeconds = 900L)
        repository.recordIdentity(1L, identity(HASH_A, size = 4096L, modified = 900L))
        repository.enqueue(listOf(1L))
        dao.changeMedia(1L, sizeBytes = 8192L, modifiedSeconds = 900L)

        val claimed = requireNotNull(repository.claimNext(chatId = 5L))

        assertEquals(8192L, claimed.expectedSizeBytes)
        assertEquals(
            "a size that moved since the hash is grounds for reading the file again, never for trusting it",
            false,
            claimed.identityIsCurrent,
        )
    }

    @Test
    fun theQueueReportsWhetherAnythingIsWaiting() = runBlocking {
        assertEquals(false, repository.hasQueuedWork())
        dao.withMedia(1L)
        repository.enqueue(listOf(1L))
        assertEquals(true, repository.hasQueuedWork())
    }

    @Test
    fun withdrawingANarrowedSelectionTouchesOnlyRowsThatNeverStarted() = runBlocking<Unit> {
        val camera = "DCIM/Camera/"
        val screenshots = "Pictures/Screenshots/"
        dao.withItemsIn(screenshots, 1L, 2L, 3L, 4L, 5L)
        repository.enqueue(listOf(1L, 2L, 3L, 4L, 5L))
        // Four reasons not to move a row, all of them out of scope for the sweep: in flight, already sent,
        // already failed, and already cancelled by a person.
        dao.forceRawState(2L, UploadState.Preparing.storageKey)
        dao.forceRawState(3L, UploadState.BackedUp.storageKey)
        dao.forceRawState(4L, UploadState.Failed.storageKey)
        dao.forceRawState(5L, UploadState.Cancelled.storageKey)

        val withdrawn = repository.releaseUnsentOutside(listOf(camera))

        assertEquals("only the one row still waiting to be claimed", 1, withdrawn)
        assertEquals(UploadState.NotBackedUp.storageKey, dao.row(1L).state)
        assertEquals(UploadState.Preparing.storageKey, dao.row(2L).state)
        assertEquals(UploadState.BackedUp.storageKey, dao.row(3L).state)
        assertEquals(UploadState.Failed.storageKey, dao.row(4L).state)
        assertEquals(UploadState.Cancelled.storageKey, dao.row(5L).state)
    }

    @Test
    fun aSelectionThatStillCoversTheItemLeavesItsRowAlone() = runBlocking<Unit> {
        val screenshots = "Pictures/Screenshots/"
        dao.withItemsIn(screenshots, 1L)
        repository.enqueue(listOf(1L))

        assertEquals(0, repository.releaseUnsentOutside(listOf(screenshots)))
        assertEquals(UploadState.Queued.storageKey, dao.row(1L).state)
    }

    /** Queues one item and claims it, which is the only way to be in `PREPARING` legitimately. */
    private suspend fun claimedRequest(): BackupRequest? {
        dao.withMedia(1L)
        repository.enqueue(listOf(1L))
        return repository.claimNext(chatId = 5L)
    }

    private fun identity(
        hash: String,
        size: Long = FakeBackupQueueDao.DEFAULT_SIZE,
        modified: Long = FakeBackupQueueDao.DEFAULT_MODIFIED,
    ) = MediaIdentity(hash, observedSizeBytes = size, observedModifiedSeconds = modified)

    private companion object {
        val HASH_A = "a".repeat(64)
        val HASH_B = "b".repeat(64)
    }
}

fun String.asState(): UploadState = UploadState.fromStorageKey(this)

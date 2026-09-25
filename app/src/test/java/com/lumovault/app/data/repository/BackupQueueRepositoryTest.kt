package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.backup.BackupQueueEntity
import com.lumovault.app.data.local.backup.BackupStateCountRow
import com.lumovault.app.data.local.backup.ClaimedBackupRow
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupItemState
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The queue's policy, exercised without a database.
 *
 * [FakeBackupQueueDao] stores rows and applies the same state writes the SQL does, so what is under
 * test is the repository's decisions — which transition is legal, how many attempts an item gets, what
 * counts as in flight — rather than Room. Those decisions are what makes a ✓ on a thumbnail mean
 * something, and they would be unreachable from a unit test if they lived inside a query.
 */
class BackupQueueRepositoryTest {
    private var clock = 1L
    private val dao = FakeBackupQueueDao { clock }
    private val repository: BackupQueueRepository =
        BackupQueueRepositoryImpl(dao, { clock }, attemptCap = 3)

    @Test
    fun enqueueAddsOnlyKnownItemsAndOnlyOnce() = runBlocking {
        dao.withMedia(1L, 2L)

        assertEquals(2, repository.enqueue(listOf(1L, 2L)))
        assertEquals("a second tap must not queue the same item twice", 0, repository.enqueue(listOf(1L, 2L)))
        assertEquals("an id the index does not know is not queueable", 0, repository.enqueue(listOf(99L)))
    }

    @Test
    fun claimTakesTheOldestWaitingItemAndRecordsWhereItIsGoing() = runBlocking {
        dao.withMedia(1L, 2L)
        clock = 10
        repository.enqueue(listOf(2L))
        clock = 20
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
    fun aSuccessWrittenFromTheWrongStateIsRefused() = runBlocking {
        val claimed = requireNotNull(claimedRequest())

        // Straight from `preparing`, with no upload in between: exactly the write that would put a ✓ on
        // a photo nothing sent.
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.markBackedUp(claimed.mediaStoreId, chatId = 5L, messageId = 1L) }
        }
        assertEquals(UploadState.Preparing, dao.row(claimed.mediaStoreId).state.asState())
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

    @Test
    fun aStateThisBuildCannotNameFailsClosedInsteadOfReQueueingItself() = runBlocking {
        val claimed = requireNotNull(claimedRequest())
        dao.forceRawState(claimed.mediaStoreId, "somesuch")

        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.markUploading(claimed.mediaStoreId) }
        }
    }

    /** Queues one item and claims it, which is the only way to be in `PREPARING` legitimately. */
    private suspend fun claimedRequest(): BackupRequest? {
        dao.withMedia(1L)
        repository.enqueue(listOf(1L))
        return repository.claimNext(chatId = 5L)
    }

    private fun String.asState(): UploadState = UploadState.fromStorageKey(this)
}

/**
 * An in-memory [BackupQueueDao].
 *
 * Mirrors the real queries rather than the real SQL: oldest-first claiming, the left join that can
 * return a row with no media behind it, and grouping for the summary. Anything the repository can do to
 * a row is applied here so the two cannot drift into agreeing by accident.
 */
private class FakeBackupQueueDao(private val clock: () -> Long) : BackupQueueDao {
    private val rows = LinkedHashMap<Long, BackupQueueEntity>()
    private val media = LinkedHashMap<Long, FakeMedia>()
    private val tick = MutableStateFlow(0)

    fun withMedia(vararg ids: Long) {
        ids.forEach { media[it] = FakeMedia(contentUri = "content://media/external/images/media/$it") }
        bump()
    }

    fun dropMedia(id: Long) {
        media.remove(id)
        bump()
    }

    fun row(id: Long): BackupQueueEntity = rows.getValue(id)

    fun forceState(id: Long, state: UploadState) = forceRawState(id, state.storageKey)

    fun forceRawState(id: Long, state: String) {
        rows.getValue(id).let { rows[id] = it.copy(state = state) }
        bump()
    }

    override fun observeStatesFor(ids: Collection<Long>): Flow<List<BackupItemState>> = snapshots().map {
        current -> current.filter { it.mediaStoreId in ids }.map { BackupItemState(it.mediaStoreId, it.state) }
    }

    override fun observeCounts(): Flow<List<BackupStateCountRow>> = snapshots().map { current ->
        current.groupingBy { it.state }.eachCount()
            .map { (state, count) -> BackupStateCountRow(state, count) }
    }

    override suspend fun insertMissing(ids: Collection<Long>, queuedState: String, now: Long) {
        ids.forEach { id ->
            if (id in media && id !in rows) {
                rows[id] = BackupQueueEntity(
                    mediaStoreId = id,
                    state = queuedState,
                    queuedAt = now,
                    updatedAt = now,
                )
            }
        }
        bump()
    }

    override suspend fun countExisting(ids: Collection<Long>): Int =
        rows.values.count { it.mediaStoreId in ids }

    override suspend fun claimOldest(
        queuedState: String,
        preparingState: String,
        chatId: Long,
        now: Long,
    ): Int {
        val oldest = rows.values
            .filter { it.state == queuedState }
            .sortedWith(compareBy({ it.queuedAt }, { it.mediaStoreId }))
            .firstOrNull() ?: return 0

        rows[oldest.mediaStoreId] = oldest.copy(
            state = preparingState,
            chatId = chatId,
            updatedAt = now,
        )
        bump()
        return 1
    }

    override suspend fun newestIn(state: String): ClaimedBackupRow? {
        val row = rows.values.filter { it.state == state }.maxByOrNull { it.updatedAt } ?: return null
        val item = media[row.mediaStoreId]

        return ClaimedBackupRow(
            mediaStoreId = row.mediaStoreId,
            chatId = row.chatId,
            attempts = row.attempts,
            stagedPath = row.stagedPath,
            stateKey = row.state,
            failureKey = row.failure,
            mediaType = item?.mediaType,
            mimeType = item?.mimeType,
            contentUri = item?.contentUri,
            displayName = item?.displayName,
            sizeBytes = item?.sizeBytes,
            width = item?.width,
            height = item?.height,
            durationMillis = item?.durationMillis,
        )
    }

    override suspend fun setState(id: Long, state: String, now: Long): Int = update(id) {
        it.copy(state = state, updatedAt = now)
    }

    override suspend fun setStagedPath(id: Long, path: String, now: Long): Int = update(id) {
        it.copy(stagedPath = path, updatedAt = now)
    }

    override suspend fun markSent(id: Long, chatId: Long, messageId: Long, sentState: String, now: Long): Int =
        update(id) {
            it.copy(
                state = sentState,
                chatId = chatId,
                messageId = messageId,
                uploadedAt = now,
                updatedAt = now,
                failure = "",
                stagedPath = "",
            )
        }

    override suspend fun settle(id: Long, state: String, attempts: Int, failure: String, now: Long): Int =
        update(id) {
            it.copy(state = state, attempts = attempts, failure = failure, updatedAt = now)
        }

    override suspend fun stagedPath(id: Long): String? = rows[id]?.stagedPath

    override suspend fun stateOf(id: Long): String? = rows[id]?.state

    override suspend fun moveAll(from: String, to: String, now: Long): Int {
        val matching = rows.values.filter { it.state == from }
        matching.forEach { rows[it.mediaStoreId] = it.copy(state = to, updatedAt = now) }
        bump()
        return matching.size
    }

    override suspend fun countIn(state: String): Int = rows.values.count { it.state == state }

    private suspend fun update(id: Long, transform: (BackupQueueEntity) -> BackupQueueEntity): Int {
        val existing = rows[id] ?: return 0
        rows[id] = transform(existing)
        bump()
        return 1
    }

    private fun snapshots(): Flow<List<BackupQueueEntity>> = tick.map { rows.values.toList() }

    private fun bump() {
        tick.value++
    }
}

/** A row of the media index, as far as the queue's join cares about it. */
private data class FakeMedia(
    val mediaType: String = MediaType.Photo.storageKey,
    val mimeType: String = "image/jpeg",
    val contentUri: String = "content://media/external/images/media/1",
    val displayName: String = "IMG_1.jpg",
    val sizeBytes: Long = 1024L,
    val width: Int = 4,
    val height: Int = 3,
    val durationMillis: Long? = null,
)

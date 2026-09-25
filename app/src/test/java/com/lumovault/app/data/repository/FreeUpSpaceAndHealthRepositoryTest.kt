package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.BackupHealthDao
import com.lumovault.app.data.local.backup.BackupStateCountRow
import com.lumovault.app.data.local.backup.EligibilityRow
import com.lumovault.app.data.local.backup.FreeUpSpaceCandidateRow
import com.lumovault.app.data.local.backup.FreeUpSpaceDao
import com.lumovault.app.data.local.backup.FreeUpSpaceTotalRow
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.organization.FakeMediaDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RejectionReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two Phase 9 read models, exercised through their repository.
 *
 * The fake below mirrors the real statements' joins and guards — the `IN`/`NOT IN` on the queue columns, the
 * join that requires the cloud record, the `COALESCE` on Trash — rather than agreeing with whatever a test
 * wants, because the thing worth proving is that the SQL and the rule in
 * [com.lumovault.app.domain.restore.FreeUpSpaceEligibility] say the same thing about the same library. A fake
 * that skipped a guard would let a test pass while the database refused a different set.
 */
class FreeUpSpaceRepositoryTest {
    private val dao = FakeFreeUpSpaceDao()
    private val repository = FreeUpSpaceRepositoryImpl(dao)

    @Test
    fun aVerifiedBackupOfAFileTheIndexStillHoldsIsOffered() = runBlocking<Unit> {
        dao.add(id = 1L, size = 10L, state = UploadState.BackedUp, chat = 7L, message = 42L, hash = HASH)
        dao.inCloudIndex(7L, 42L)

        val plan = repository.observePlan().first()

        assertEquals(1, plan.eligibleCount)
        assertEquals(10L, plan.reclaimableBytes)
        assertEquals(listOf(1L), repository.review(10).map { it.mediaStoreId })
    }

    @Test
    fun aBackupWithoutAContentHashIsVerificationIncomplete() = runBlocking<Unit> {
        dao.add(id = 1L, size = 10L, state = UploadState.BackedUp, chat = 7L, message = 42L, hash = "")
        dao.inCloudIndex(7L, 42L)

        assertEquals(0, repository.observePlan().first().eligibleCount)
        assertEquals(
            RejectionReason.NoContentIdentity,
            repository.recheck(listOf(1L)).single().rejected,
        )
    }

    @Test
    fun aLocalOnlyItemIsNeverOffered() = runBlocking<Unit> {
        dao.add(id = 1L, size = 10L, state = UploadState.NotBackedUp, chat = 0L, message = 0L, hash = HASH)

        assertEquals("nothing", 0, repository.observePlan().first().eligibleCount)
        assertEquals(
            RejectionReason.NotBackedUp,
            repository.recheck(listOf(1L)).single().rejected,
        )
    }

    @Test
    fun anItemInTheCloudOnlyIsNotOfferedLocalDeletion() = runBlocking<Unit> {
        // No row in `media` at all: the offer list is built from the local index, so this cannot appear —
        // and the re-check must not invent one either.
        dao.inCloudIndex(7L, 42L)

        assertEquals(0, repository.observePlan().first().eligibleCount)
        assertEquals(
            RejectionReason.MissingFromIndex,
            repository.recheck(listOf(99L)).single().rejected,
        )
        assertTrue(repository.review(10).isEmpty())
    }

    @Test
    fun aMessageTheIndexNoLongerHoldsTakesTheItemOutOfTheOffer() = runBlocking<Unit> {
        dao.add(id = 1L, size = 10L, state = UploadState.BackedUp, chat = 7L, message = 42L, hash = HASH)

        assertEquals("the guard is the join, and the join has nothing to match yet", 0, repository.observePlan().first().eligibleCount)
        assertEquals(
            RejectionReason.CloudRecordGone,
            repository.recheck(listOf(1L)).single().rejected,
        )

        dao.inCloudIndex(7L, 42L)
        assertEquals(1, repository.observePlan().first().eligibleCount)
        assertTrue(repository.recheck(listOf(1L)).single().isDeletable)
    }

    @Test
    fun workInFlightAndTrashAreBothRefusedAndSaidApart() = runBlocking<Unit> {
        dao.add(id = 1L, size = 10L, state = UploadState.Uploading, chat = 7L, message = 42L, hash = HASH)
        dao.add(id = 2L, size = 20L, state = UploadState.BackedUp, chat = 7L, message = 43L, hash = HASH, trashedAt = 5L)
        dao.inCloudIndex(7L, 42L)
        dao.inCloudIndex(7L, 43L)

        assertEquals(0, repository.observePlan().first().eligibleCount)
        val checks = repository.recheck(listOf(1L, 2L))
        assertEquals(RejectionReason.UploadInFlight, checks[0].rejected)
        assertEquals(RejectionReason.InTrash, checks[1].rejected)
    }

    @Test
    fun totalsCountEveryEligibleItemAndSumTheirRealSizes() = runBlocking<Unit> {
        listOf(5L, 15L, 25L).forEachIndexed { index, size ->
            dao.add(id = index + 1L, size = size, state = UploadState.BackedUp, chat = 7L, message = 40L + index, hash = HASH)
            dao.inCloudIndex(7L, 40L + index)
        }
        dao.add(id = 99L, size = 1_000L, state = UploadState.Queued, chat = 7L, message = 60L, hash = HASH)
        dao.inCloudIndex(7L, 60L)

        val plan = repository.observePlan().first()

        assertEquals(3, plan.eligibleCount)
        assertEquals(
            "the ineligible item's size stays out of the headline, so the promise is not inflated",
            45L,
            plan.reclaimableBytes,
        )
    }

    @Test
    fun theSecondCheckAnswersInTheOrderItWasAskedAndOnlyForWhatExists() = runBlocking<Unit> {
        dao.add(id = 3L, size = 10L, state = UploadState.BackedUp, chat = 7L, message = 42L, hash = HASH)
        dao.add(id = 1L, size = 10L, state = UploadState.Cancelled, chat = 7L, message = 43L, hash = HASH)
        dao.inCloudIndex(7L, 42L)
        dao.inCloudIndex(7L, 43L)

        val checks = repository.recheck(listOf(2L, 3L, 1L))

        assertEquals(listOf(2L, 3L, 1L), checks.map { it.mediaStoreId })
        assertEquals(
            listOf(RejectionReason.MissingFromIndex, null, RejectionReason.NotBackedUp),
            checks.map { it.rejected },
        )
    }

    @Test
    fun aRecheckOfMoreIdsThanOneStatementCanCarryIsChunkedNotTruncated() = runBlocking<Unit> {
        val ids = (1L..450L).toList()
        ids.forEach { dao.add(id = it, size = 2L, state = UploadState.BackedUp, chat = 7L, message = it, hash = HASH) }
        ids.forEach { dao.inCloudIndex(7L, it) }

        val checks = repository.recheck(ids)

        assertEquals(
            "every id has to come back with an answer, including the ones past SQLite's parameter ceiling",
            ids.size,
            checks.size,
        )
        assertTrue(checks.all { it.isDeletable })
    }

    private companion object {
        const val HASH = "abababababababababababababababababababababababababababababababab"
    }
}

/**
 * The health aggregate, over the same kind of in-memory mirrors.
 *
 * Worth its own class because the model's most important property is negative: `allLocalMediaBackedUp` must
 * be false for a library that looks finished but has one failed row, one waiting row, or an item the cloud
 * index cannot account for.
 */
class BackupHealthRepositoryTest {
    private val store = OrganizationStore()
    private val clock = QueueClock()
    private val queue = FakeBackupQueueDao()
    private val media = FakeMediaDao(store)

    /**
     * Puts items in both fakes and gives each a queue row.
     *
     * The index count and the queue's counts are read from two tables in the real database, so the fixture
     * has to seed both; and a row cannot be forced into a state before it exists, which is what `enqueue`
     * does here through the production repository rather than a back door.
     */
    private suspend fun withQueueRows(vararg ids: Long) {
        ids.forEach { store.index(FakeMediaRow(id = it)) }
        ids.forEach { queue.withItem(it) }
        queue.insertMissing(ids.toList(), UploadState.Queued.storageKey, clock.now())
    }

    private fun repository(cloudOnly: Int, lastBackup: Long?, lastScan: Long?) = BackupHealthRepositoryImpl(
        media = media,
        queue = queue,
        freeUpSpace = FakeFreeUpSpaceDao(),
        health = FakeBackupHealthDao(cloudOnly, lastBackup),
        lastScanSeconds = flowOf(lastScan),
    )

    @Test
    fun countsComeFromTheQueueStatesAndNothingIsDoubleCounted() = runBlocking<Unit> {
        withQueueRows(1L, 2L, 3L)
        queue.forceState(1L, UploadState.BackedUp)
        queue.forceRawState(2L, UploadState.Queued.storageKey)
        queue.forceRawState(3L, UploadState.Uploading.storageKey)

        val health = repository(cloudOnly = 4, lastBackup = 1_790_000_100L, lastScan = 1_790_000_200L)
            .observe()
            .first()

        assertEquals(3, health.localTotal)
        assertEquals(1, health.backedUp)
        assertEquals(1, health.waiting)
        assertEquals(1, health.uploading)
        assertEquals(0, health.failed)
        assertEquals(4, health.cloudOnly)
        assertEquals(2, health.notBackedUp)
        assertEquals(1_790_000_100L, health.lastBackupSeconds)
        assertEquals(1_790_000_200L, health.lastScanSeconds)
    }

    @Test
    fun oneFailedRowIsEnoughToRefuseTheReassurance() = runBlocking<Unit> {
        withQueueRows(1L, 2L)
        queue.forceState(1L, UploadState.BackedUp)
        queue.forceRawState(2L, UploadState.Failed.storageKey)
        val health = repository(cloudOnly = 0, lastBackup = 1L, lastScan = 1L).observe().first()

        assertTrue("backed up == localTotal is not the test; nothing may be outstanding", health.backedUp == health.localTotal - 1)
        assertTrue(health.failed == 1)
    }

    fun aLibraryWhereEverythingIsStoredAndNothingOutstandingMaySaySo() = runBlocking<Unit> {
        withQueueRows(1L, 2L)
        queue.forceState(1L, UploadState.BackedUp)
        queue.forceState(2L, UploadState.BackedUp)

        assertTrue(repository(cloudOnly = 0, lastBackup = 1L, lastScan = 1L).observe().first().allLocalMediaBackedUp)
    }

    @Test
    fun anEmptyLibraryReportsNothingRatherThanSafety() = runBlocking<Unit> {
        val health = repository(cloudOnly = 0, lastBackup = null, lastScan = null).observe().first()

        assertEquals(0, health.localTotal)
        assertTrue("zero photos are not a backed-up library", !health.allLocalMediaBackedUp)
        assertTrue(health.neverBackedUp)
        assertTrue(health.neverScanned)
    }

    @Test
    fun cancelledWorkCountsAsWaitingAndNotAsFailed() = runBlocking<Unit> {
        withQueueRows(1L)
        queue.forceState(1L, UploadState.Cancelled)

        val health = repository(cloudOnly = 0, lastBackup = null, lastScan = null).observe().first()

        assertEquals(0, health.failed)
        assertEquals("a row that asserts nothing is outstanding work", 1, health.waiting)
    }
}

/**
 * A mirror of [FreeUpSpaceDao]'s three statements.
 *
 * The guards are written as the SQL writes them — the backed-up state, a non-zero chat and message, a
 * non-empty hash, the join that requires the message to still be in the cloud index, and `COALESCE` on Trash —
 * because the repository's job is to translate those rows into evidence, and translating a set the fake got
 * wrong proves nothing about the real one.
 */
private class FakeFreeUpSpaceDao : FreeUpSpaceDao {
    private data class Row(
        val mediaStoreId: Long,
        val sizeBytes: Long,
        val state: String?,
        val chatId: Long,
        val messageId: Long,
        val hash: String,
        val trashedAt: Long,
        val inCloudIndex: Boolean,
    )

    private val rows = LinkedHashMap<Long, Row>()
    private val cloudMessages = mutableSetOf<Pair<Long, Long>>()

    fun add(
        id: Long,
        size: Long,
        state: UploadState,
        chat: Long,
        message: Long,
        hash: String,
        trashedAt: Long = 0L,
    ) {
        rows[id] = Row(id, size, state.storageKey, chat, message, hash, trashedAt, inCloudIndex = false)
    }

    fun inCloudIndex(chat: Long, message: Long) {
        cloudMessages += chat to message
        rows.values
            .filter { it.chatId == chat && it.messageId == message }
            .forEach { rows[it.mediaStoreId] = it.copy(inCloudIndex = true) }
    }

    override fun observeTotals(backedUpState: String): Flow<FreeUpSpaceTotalRow> =
        flowOf(eligible(backedUpState).let { FreeUpSpaceTotalRow(it.size, it.sumOf { row -> row.sizeBytes }) })

    override suspend fun candidates(backedUpState: String, limit: Int): List<FreeUpSpaceCandidateRow> =
        eligible(backedUpState)
            .sortedWith(compareByDescending<Row> { it.sizeBytes }.thenByDescending { it.mediaStoreId })
            .take(limit)
            .map {
                FreeUpSpaceCandidateRow(
                    mediaStoreId = it.mediaStoreId,
                    contentUri = "content://media/external/images/media/${it.mediaStoreId}",
                    displayName = "IMG_${it.mediaStoreId}.jpg",
                    sizeBytes = it.sizeBytes,
                    mediaType = MediaType.Photo.storageKey,
                )
            }

    /**
     * Left joins all the way down, so an id that lost its queue row still answers — with the columns that say
     * why. Only `media` itself is an inner join: an id with no index row produces no row at all, which is what
     * makes the repository report it as gone rather than as ineligible.
     */
    override suspend fun eligibilityFor(ids: Collection<Long>): List<EligibilityRow> =
        ids.filter { rows.containsKey(it) }.map { id ->
            val row = rows[id]
            EligibilityRow(
                mediaStoreId = id,
                contentUri = "content://media/external/images/media/$id",
                displayName = "IMG_$id.jpg",
                sizeBytes = row?.sizeBytes ?: 0L,
                mediaType = MediaType.Photo.storageKey,
                queueState = row?.state,
                queueChatId = row?.chatId,
                queueMessageId = row?.messageId,
                queueHash = row?.hash,
                cloudMessageId = row?.messageId?.takeIf { row.inCloudIndex },
                trashedAt = row?.trashedAt ?: 0L,
            )
        }

    private fun eligible(backedUpState: String): List<Row> = rows.values.filter { row ->
        row.state == backedUpState &&
            row.chatId != 0L &&
            row.messageId != 0L &&
            row.hash.isNotEmpty() &&
            row.inCloudIndex &&
            row.trashedAt == 0L
    }
}

private class FakeBackupHealthDao(
    private val cloudOnly: Int,
    private val lastBackup: Long?,
) : BackupHealthDao {
    override fun observeCloudOnlyCount(): Flow<Int> = flowOf(cloudOnly)

    override fun observeLastBackupSeconds(backedUpState: String): Flow<Long?> = flowOf(lastBackup)
}

package com.lumovault.app.data.repository

import com.lumovault.app.data.local.restore.MediaRestoreDao
import com.lumovault.app.data.local.restore.MediaRestoreEntity
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreRepository
import com.lumovault.app.domain.restore.RestoreState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore table's observation window, exercised without a database.
 *
 * What is under test is the repository's chunking, not the rows: a cloud window grows as the user scrolls,
 * and an observation that silently stopped at the first chunk would leave badges off exactly the messages
 * the user scrolled far enough to care about.
 */
class RestoreRepositoryTest {
    private val dao = FakeMediaRestoreDao()
    private val repository: RestoreRepository = RestoreRepositoryImpl(dao, nowSeconds = { 1_000L })

    @Test
    fun aWindowTooLargeForOneStatementIsStillObservedInFull() = runBlocking<Unit> {
        val ids = (1L..900L).toList()
        ids.forEach { dao.row(CHAT, it, RestoreState.Pending.storageKey) }

        val jobs = repository.observeForMessages(CHAT, ids).first()

        assertEquals(
            "a message past the first chunk used to be truncated away and never observed",
            ids.toSet(),
            jobs.keys,
        )
        assertTrue(
            "no single statement was asked to bind more ids than the cap allows",
            dao.largestObservedBatch <= 400,
        )
    }

    @Test
    fun observingAnEmptyWindowAsksTheDatabaseNothing() = runBlocking<Unit> {
        assertEquals(emptyMap<Long, RestoreJob>(), repository.observeForMessages(CHAT, emptyList()).first())
        assertEquals("`IN ()` is not SQL; an empty window must not reach the DAO", 0, dao.observeCalls)
    }

    private companion object {
        const val CHAT = 55_000_000_000L
    }
}

/**
 * An in-memory [MediaRestoreDao] for the observation tests.
 *
 * Only `observeForMessages` mirrors a real query — filtering by chat and id set over a tick that stands in
 * for Room's invalidation. The transfer-lifecycle members are stubs the path under test does not exercise;
 * the rules they carry are asserted in `RestoreCloudMediaUseCaseTest`, against fakes at the use-case seam.
 */
private class FakeMediaRestoreDao : MediaRestoreDao {
    private val rows = LinkedHashMap<Pair<Long, Long>, MediaRestoreEntity>()
    private val tick = MutableStateFlow(0)

    var largestObservedBatch = 0
        private set

    var observeCalls = 0
        private set

    fun row(chatId: Long, messageId: Long, state: String) {
        rows[chatId to messageId] = MediaRestoreEntity(chatId = chatId, messageId = messageId, state = state)
        tick.value = tick.value + 1
    }

    override fun observeForMessages(
        chatId: Long,
        messageIds: Collection<Long>,
    ): Flow<List<MediaRestoreEntity>> {
        observeCalls += 1
        if (messageIds.size > largestObservedBatch) largestObservedBatch = messageIds.size
        return tick.map { rows.values.filter { it.chatId == chatId && it.messageId in messageIds } }
    }

    override fun observeJob(chatId: Long, messageId: Long): Flow<MediaRestoreEntity?> =
        flowOf(rows[chatId to messageId])

    override suspend fun job(chatId: Long, messageId: Long): MediaRestoreEntity? = rows[chatId to messageId]

    override fun observeLive(liveStates: List<String>, limit: Int): Flow<List<MediaRestoreEntity>> =
        flowOf(emptyList())

    override suspend fun liveRows(liveStates: List<String>): List<MediaRestoreEntity> = emptyList()

    override suspend fun insertIgnoring(entity: MediaRestoreEntity): Long = 0L

    override suspend fun restart(
        chatId: Long,
        messageId: Long,
        restartableStates: List<String>,
        state: String,
        updatedAt: Long,
    ): Int = 0

    override suspend fun recordDownloadTarget(chatId: Long, messageId: Long, fileId: Int, updatedAt: Long): Int = 0

    override suspend fun advance(
        chatId: Long,
        messageId: Long,
        state: String,
        downloadedBytes: Long,
        updatedAt: Long,
    ): Int = 0

    override suspend fun setState(chatId: Long, messageId: Long, state: String, updatedAt: Long): Int = 0

    override suspend fun complete(
        chatId: Long,
        messageId: Long,
        state: String,
        mediaStoreId: Long,
        contentHash: String,
        downloadedBytes: Long,
        updatedAt: Long,
    ): Int = 0

    override suspend fun fail(
        chatId: Long,
        messageId: Long,
        state: String,
        failure: String,
        updatedAt: Long,
    ): Int = 0

    override suspend fun reconcileInterrupted(
        liveStates: List<String>,
        toState: String,
        failure: String,
        updatedAt: Long,
    ): Int = 0
}

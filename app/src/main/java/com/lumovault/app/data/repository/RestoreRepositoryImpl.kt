package com.lumovault.app.data.repository

import com.lumovault.app.data.local.restore.MediaRestoreDao
import com.lumovault.app.data.local.restore.MediaRestoreEntity
import com.lumovault.app.domain.restore.CloudRestoreTarget
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreRepository
import com.lumovault.app.domain.restore.RestoreState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The restore table, with the transition decisions kept where they can be read.
 *
 * [RESTARTABLE_STATES] is the one list that decides when a second tap may start a transfer, and it is here
 * rather than at each call site precisely because a call site that got it wrong would run two downloads of
 * the same message and write two local files. A live row is never restarted, and `take` reports that by
 * returning null.
 */
class RestoreRepositoryImpl(
    private val dao: MediaRestoreDao,
    private val nowSeconds: () -> Long = System::currentTimeMillis,
) : RestoreRepository {

    override fun observeForMessages(chatId: Long, messageIds: Collection<Long>): Flow<Map<Long, RestoreJob>> =
        dao.observeForMessages(chatId, messageIds.take(MAX_IDS_PER_QUERY))
            .map { rows -> rows.map { it.toJob() }.associateBy { it.messageId } }

    override fun observeJob(chatId: Long, messageId: Long): Flow<RestoreJob?> =
        dao.observeJob(chatId, messageId).map { it?.toJob() }

    override fun observeLive(limit: Int): Flow<List<RestoreJob>> =
        dao.observeLive(LIVE_STATES, limit.coerceAtLeast(1)).map { rows -> rows.map { it.toJob() } }

    override suspend fun job(chatId: Long, messageId: Long): RestoreJob? = dao.job(chatId, messageId)?.toJob()

    override suspend fun take(target: CloudRestoreTarget): RestoreJob? {
        if (!target.isRestorable) return null

        val requested = nowSeconds()
        val inserted = dao.insertIgnoring(
            MediaRestoreEntity(
                chatId = target.chatId,
                messageId = target.messageId,
                expectedSizeBytes = target.expectedSizeBytes,
                requestedAtSeconds = requested,
                updatedAtSeconds = requested,
            ),
        )
        if (inserted >= 0L) return job(target.chatId, target.messageId)

        // The row existed. Restart it only if it had finished — and if neither branch applies, this is a
        // second tap on a download already running, which has to be reported as nothing having happened.
        val existing = dao.job(target.chatId, target.messageId) ?: return null
        if (existing.state !in RESTARTABLE_STATES) return null

        val restarted = dao.restart(
            chatId = target.chatId,
            messageId = target.messageId,
            restartableStates = RESTARTABLE_STATES,
            state = RestoreState.Pending.storageKey,
            updatedAt = requested,
        )
        return if (restarted > 0) job(target.chatId, target.messageId) else null
    }

    override suspend fun recordDownloadTarget(chatId: Long, messageId: Long, tdlibFileId: Int) {
        if (tdlibFileId <= 0) return
        dao.recordDownloadTarget(chatId, messageId, tdlibFileId, nowSeconds())
    }

    override suspend fun markDownloading(chatId: Long, messageId: Long, downloadedBytes: Long) {
        dao.advance(
            chatId = chatId,
            messageId = messageId,
            state = RestoreState.Downloading.storageKey,
            downloadedBytes = downloadedBytes.coerceAtLeast(0L),
            updatedAt = nowSeconds(),
        )
    }

    override suspend fun markState(chatId: Long, messageId: Long, state: RestoreState) {
        dao.setState(chatId, messageId, state.storageKey, nowSeconds())
    }

    override suspend fun complete(
        chatId: Long,
        messageId: Long,
        mediaStoreId: Long,
        contentHash: String,
        downloadedBytes: Long,
    ) {
        dao.complete(
            chatId = chatId,
            messageId = messageId,
            state = RestoreState.Completed.storageKey,
            mediaStoreId = mediaStoreId,
            contentHash = contentHash,
            downloadedBytes = downloadedBytes,
            updatedAt = nowSeconds(),
        )
    }

    override suspend fun fail(chatId: Long, messageId: Long, failure: RestoreFailureKind) {
        dao.fail(
            chatId = chatId,
            messageId = messageId,
            state = RestoreState.Failed.storageKey,
            failure = failure.name,
            updatedAt = nowSeconds(),
        )
    }

    override suspend fun cancel(chatId: Long, messageId: Long) {
        dao.fail(
            chatId = chatId,
            messageId = messageId,
            state = RestoreState.Cancelled.storageKey,
            failure = RestoreFailureKind.Cancelled.name,
            updatedAt = nowSeconds(),
        )
    }

    override suspend fun reconcileInterrupted(): List<Int> {
        val abandoned = dao.liveRows(LIVE_STATES)
        if (abandoned.isEmpty()) return emptyList()

        dao.reconcileInterrupted(
            liveStates = LIVE_STATES,
            toState = RestoreState.Failed.storageKey,
            // `Cancelled`, not a new "interrupted" state: the honest statement is that the app stopped
            // working on this, that nothing about the file is wrong, and that asking again is the fix.
            failure = RestoreFailureKind.Cancelled.name,
            updatedAt = nowSeconds(),
        )
        return abandoned.map { it.tdlibFileId }.filter { it > 0 }
    }

    private fun MediaRestoreEntity.toJob() = RestoreJob(
        chatId = chatId,
        messageId = messageId,
        state = RestoreState.fromStorageKey(state),
        downloadedBytes = downloadedBytes,
        expectedSizeBytes = expectedSizeBytes,
        tdlibFileId = tdlibFileId,
        mediaStoreId = mediaStoreId,
        failure = failure.takeIf { it.isNotBlank() }?.let { name ->
            RestoreFailureKind.entries.firstOrNull { it.name == name }
        },
    )

    private companion object {
        const val MAX_IDS_PER_QUERY = 400

        val LIVE_STATES = listOf(
            RestoreState.Pending.storageKey,
            RestoreState.Downloading.storageKey,
            RestoreState.Verifying.storageKey,
            RestoreState.Saving.storageKey,
        )

        val RESTARTABLE_STATES = listOf(
            RestoreState.Completed.storageKey,
            RestoreState.Failed.storageKey,
            RestoreState.Cancelled.storageKey,
        )
    }
}

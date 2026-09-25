package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.BackupQueueDao
import com.lumovault.app.data.local.backup.ClaimedBackupRow
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupIdentityCandidate
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.backup.UploadTransitions
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.RemoteBackup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * The queue's policy, in one place, over a DAO that only stores.
 *
 * Two decisions live here rather than at the call sites. Legality: every state change is checked
 * against what the stored row actually says, so a ✓ cannot appear on a thumbnail because some path
 * wrote `BACKED_UP` to a row that never sent anything. And whether a failure deserves another attempt,
 * which needs the row's own attempt count and the queue's cap — a worker that decided that for itself
 * would let the two disagree about how many times a file was refused.
 */
class BackupQueueRepositoryImpl(
    private val dao: BackupQueueDao,
    private val nowSeconds: () -> Long,
    private val attemptCap: Int = ATTEMPT_CAP,
) : BackupQueueRepository {

    override fun observeStatesFor(mediaStoreIds: Collection<Long>): Flow<Map<Long, UploadState>> =
        dao.observeStatesFor(mediaStoreIds).map { rows ->
            rows.associate { it.mediaStoreId to it.state }
        }

    override fun observeSummary(): Flow<BackupQueueSummary> =
        dao.observeCounts().map { rows ->
            rows.fold(BackupQueueSummary()) { summary, row ->
                when (UploadState.fromStorageKey(row.stateKey)) {
                    UploadState.Queued -> summary.copy(queued = summary.queued + row.itemCount)
                    UploadState.Preparing, UploadState.Uploading ->
                        summary.copy(inFlight = summary.inFlight + row.itemCount)

                    UploadState.BackedUp -> summary.copy(backedUp = summary.backedUp + row.itemCount)
                    UploadState.Failed -> summary.copy(failed = summary.failed + row.itemCount)

                    // Known content with nowhere stored. It is the majority of a recognised library, and
                    // counting it would put a number on the progress line that describes photos the user
                    // never asked to back up.
                    UploadState.NotBackedUp -> summary

                    // Withdrawn rather than pending or done. Counting it anywhere would put a number
                    // on screen that no user action explains.
                    UploadState.Cancelled -> summary
                }
            }
        }

    override suspend fun enqueue(mediaStoreIds: Collection<Long>): Int {
        if (mediaStoreIds.isEmpty()) return 0
        val now = nowSeconds()

        // Two populations, because recognition may have met these items first. A tap on "Back Up" has to
        // reach into both: rows that exist and are merely *known*, and items with no row at all yet.
        val promoted = dao.promoteRecognized(
            ids = mediaStoreIds,
            queuedState = UploadState.Queued.storageKey,
            fromState = UploadState.NotBackedUp.storageKey,
            now = now,
        )

        // Room gives an INSERT no row count, so the difference across the statement is what "how many did
        // you take" means for the rest — and it counts only ids that ended up with a row, so an item that
        // left the media index between the tap and the query is reported as not queued rather than as
        // queued and then failed.
        val before = dao.countExisting(mediaStoreIds)
        dao.insertMissing(mediaStoreIds, UploadState.Queued.storageKey, now)
        return promoted + (dao.countExisting(mediaStoreIds) - before)
    }

    override suspend fun identityCandidates(
        includeWholeLibrary: Boolean,
        limit: Int,
    ): List<BackupIdentityCandidate> {
        if (limit <= 0) return emptyList()
        val rows = if (includeWholeLibrary) {
            dao.libraryIdentityCandidates(limit)
        } else {
            dao.recordIdentityCandidates(limit)
        }
        return rows.map { row ->
            BackupIdentityCandidate(
                mediaStoreId = row.mediaStoreId,
                contentUri = row.contentUri,
                mediaType = MediaType.fromStorageKey(row.mediaType),
                mimeType = row.mimeType,
                displayName = row.displayName,
                sizeBytes = row.sizeBytes,
                modifiedSeconds = row.modifiedSeconds,
                knownHash = row.knownHash.orEmpty(),
                state = row.stateKey?.let(UploadState::fromStorageKey),
            )
        }
    }

    override suspend fun recordIdentity(mediaStoreId: Long, identity: MediaIdentity) {
        dao.recordIdentity(
            id = mediaStoreId,
            notBackedUpState = UploadState.NotBackedUp.storageKey,
            hash = identity.contentHash,
            sizeBytes = identity.observedSizeBytes,
            modifiedSeconds = identity.observedModifiedSeconds,
            hashedAt = nowSeconds(),
        )
    }

    override suspend fun adoptRemote(mediaStoreId: Long, remote: RemoteBackup): Boolean =
        dao.adoptFromRemote(
            id = mediaStoreId,
            chatId = remote.chatId,
            messageId = remote.messageId,
            backedUpState = UploadState.BackedUp.storageKey,
            fromStates = ADOPTABLE_STATES,
            now = nowSeconds(),
        ) > 0

    override suspend fun residentBackupFor(remote: RemoteBackup, manifestHash: String): Long? =
        dao.residentBackupFor(remote.chatId, remote.messageId, manifestHash.lowercase())

    override suspend fun autoBackupCandidates(
        source: BackupSource?,
        folders: List<String>,
        limit: Int,
    ): List<Long> {
        // `none` and an unanswered question are the same refusal; `selected_folders` with nothing selected
        // is a third, and it would otherwise read as "everything" through the empty-list branch below.
        val includeAll = source == BackupSource.AllMedia
        if (source == null || source == BackupSource.NotNow || (!includeAll && folders.isEmpty())) {
            return emptyList()
        }
        return dao.autoBackupCandidates(
            openState = UploadState.NotBackedUp.storageKey,
            includeAll = includeAll,
            folders = folders,
            limit = limit.coerceAtMost(MAX_AUTO_CANDIDATES).coerceAtLeast(1),
        )
    }

    override suspend fun recordRestored(
        mediaStoreId: Long,
        remote: RemoteBackup,
        identity: MediaIdentity,
    ): Boolean {
        val now = nowSeconds()
        dao.recordRestored(
            id = mediaStoreId,
            chatId = remote.chatId,
            messageId = remote.messageId,
            hash = identity.contentHash,
            sizeBytes = identity.observedSizeBytes,
            modifiedSeconds = identity.observedModifiedSeconds,
            backedUpState = UploadState.BackedUp.storageKey,
            settleableStates = ADOPTABLE_STATES,
            now = now,
        )
        // Read back rather than assumed: the statement can be skipped by the same guard that makes it
        // safe, and a restore that reported success on a row a worker still owns would let Free Up Space
        // delete a file the queue believes it is sending.
        return dao.stateOf(mediaStoreId) == UploadState.BackedUp.storageKey
    }

    override suspend fun revokeAssociation(mediaStoreId: Long): Boolean =
        dao.revokeAssociation(
            id = mediaStoreId,
            toState = UploadState.NotBackedUp.storageKey,
            fromStates = REVOCABLE_STATES,
            now = nowSeconds(),
        ) > 0

    override suspend fun hasQueuedWork(): Boolean =
        dao.countIn(UploadState.Queued.storageKey) > 0

    override suspend fun claimNext(chatId: Long): BackupRequest? {
        val now = nowSeconds()
        val claimed = dao.claimOldest(
            queuedState = UploadState.Queued.storageKey,
            preparingState = UploadState.Preparing.storageKey,
            chatId = chatId,
            now = now,
        )
        if (claimed == 0) return null

        val row = dao.newestIn(UploadState.Preparing.storageKey) ?: return null
        val mediaType = row.mediaType?.let { key -> MediaType.entries.firstOrNull { it.storageKey == key } }
        val contentUri = row.contentUri

        // The item left the media index between queueing and claiming. Fail this row and take the
        // next, so one deleted file cannot wedge the two hundred behind it.
        if (mediaType == null || contentUri == null) {
            dao.settle(
                id = row.mediaStoreId,
                state = UploadState.Failed.storageKey,
                attempts = row.attempts,
                failure = BackupFailureKind.SourceMissing.name,
                now = now,
            )
            return claimNext(chatId)
        }

        return BackupRequest(
            mediaStoreId = row.mediaStoreId,
            mediaType = mediaType,
            mimeType = row.mimeType.orEmpty(),
            contentUri = contentUri,
            displayName = row.displayName.orEmpty(),
            expectedSizeBytes = row.sizeBytes ?: 0L,
            modifiedSeconds = row.modifiedSeconds ?: 0L,
            width = row.width ?: 0,
            height = row.height ?: 0,
            durationMillis = row.durationMillis,
            state = UploadState.Preparing,
            contentHash = row.contentHash,
            contentSizeBytes = row.contentSizeBytes,
            contentModifiedSeconds = row.contentModifiedSeconds,
            telegramChatId = row.chatId,
            telegramMessageId = row.messageId,
            attempts = row.attempts,
            failure = row.failureKey.toFailure(),
        )
    }

    override suspend fun markUploading(mediaStoreId: Long) {
        transition(mediaStoreId, UploadState.Uploading)
    }

    override suspend fun markStaged(mediaStoreId: Long, path: String) {
        dao.setStagedPath(mediaStoreId, path, nowSeconds())
    }

    override suspend fun markBackedUp(mediaStoreId: Long, chatId: Long, messageId: Long) {
        requireTransition(mediaStoreId, UploadState.BackedUp)
        dao.markSent(mediaStoreId, chatId, messageId, UploadState.BackedUp.storageKey, nowSeconds())
    }

    override suspend fun release(request: BackupRequest, failure: BackupFailure) {
        val attempts = request.attempts + 1
        val willRetry = failure.retryable && attempts < attemptCap
        dao.settle(
            id = request.mediaStoreId,
            state = (if (willRetry) UploadState.Queued else UploadState.Failed).storageKey,
            attempts = attempts,
            failure = failure.kind.name,
            now = nowSeconds(),
        )
    }

    override suspend fun stagedPathOf(mediaStoreId: Long): String =
        dao.stagedPath(mediaStoreId).orEmpty()

    override suspend fun cancelQueued(): Int =
        dao.moveAll(UploadState.Queued.storageKey, UploadState.Cancelled.storageKey, nowSeconds())

    override suspend fun requeueFailed(): Int =
        dao.moveAll(UploadState.Failed.storageKey, UploadState.Queued.storageKey, nowSeconds())

    override suspend fun reconcileInterrupted(): Int {
        val now = nowSeconds()
        return dao.moveAll(UploadState.Preparing.storageKey, UploadState.Queued.storageKey, now) +
            dao.moveAll(UploadState.Uploading.storageKey, UploadState.Queued.storageKey, now)
    }

    private suspend fun transition(mediaStoreId: Long, to: UploadState) {
        requireTransition(mediaStoreId, to)
        dao.setState(mediaStoreId, to.storageKey, nowSeconds())
    }

    /**
     * Reads the row's state instead of trusting the caller to know it.
     *
     * The cost is one query per transition on a queue that runs a single item at a time, which is
     * nothing beside an upload. What it buys is that a write contradicting the stored state throws
     * rather than producing a database that describes a backup that never happened.
     */
    private suspend fun requireTransition(mediaStoreId: Long, to: UploadState) {
        val stored = dao.stateOf(mediaStoreId)
            ?: throw IllegalStateException("no backup queue row for $mediaStoreId")
        val from = UploadState.fromStorageKey(stored)
        check(UploadTransitions.isLegal(from, to)) {
            "illegal backup transition ${from.storageKey} -> ${to.storageKey} for $mediaStoreId"
        }
    }

    private companion object {
        /**
         * Enough to ride out a network handover on a moving train without treating a refused file as
         * something the network will eventually fix.
         */
        const val ATTEMPT_CAP = 4

        /**
         * The states a worker does not own. A row in one of these can be settled by recognition alone,
         * because nothing is in flight to contradict the write — and every one of them is a state the user
         * can legitimately see a ✓ arrive on.
         */
        val ADOPTABLE_STATES: List<String> = listOf(
            UploadState.NotBackedUp,
            UploadState.Queued,
            UploadState.Failed,
            UploadState.Cancelled,
            UploadState.BackedUp,
        ).map(UploadState::storageKey)

        /**
         * The states that assert a stored home, and are therefore the only ones a revoked association
         * should ever be taken from. Queued work is the user's to cancel, and in-flight work a worker's to
         * settle — neither is a scan's to rewrite.
         */
        val REVOCABLE_STATES: List<String> = listOf(UploadState.BackedUp.storageKey)

        /**
         * How much of a library one pass may take into the queue.
         *
         * A user opting in on a phone with 90,000 photos has agreed to back them up, not to have 90,000
         * rows written in one go — and each queued row is a row the recognition pass then reads. The cap
         * makes the first pass finite and the next pass real, which is also what lets progress be reported
         * as something other than "eventually".
         */
        const val MAX_AUTO_CANDIDATES = 500
    }
}

/**
 * An unknown stored name is [BackupFailureKind.Unknown] rather than a crash: the failure field is a
 * diagnostic, and losing it after an app downgrade must not make a queue row unreadable.
 */
private fun String?.toFailure(): BackupFailure? {
    val key = this?.takeIf { it.isNotBlank() } ?: return null
    return BackupFailure(BackupFailureKind.entries.firstOrNull { it.name == key } ?: BackupFailureKind.Unknown)
}

package com.lumovault.app.domain.backup

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.RemoteBackup
import kotlinx.coroutines.flow.Flow

/**
 * One queued backup, as the queue stores it.
 *
 * Keyed by [mediaStoreId] rather than by a queue-local id: PRD section 61's second table is meant to
 * be joined to the media index, and a row that cannot be traced back to the item it came from cannot
 * be reconciled after a reinstall or a MediaStore renumbering.
 */
data class BackupRequest(
    val mediaStoreId: Long,
    val mediaType: MediaType,
    val mimeType: String,
    val contentUri: String,
    val displayName: String,
    /** MediaStore's idea of the size, used only to plan the staging copy. */
    val expectedSizeBytes: Long,
    /** MediaStore's modification time, which the manifest records alongside the hash it was taken with. */
    val modifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val state: UploadState,
    /**
     * The identity of the content this request is about to send, as recognition recorded it, or empty when
     * nothing has identified it yet. The upload path refuses to send without one, because an upload with no
     * hash is a backup that cannot be recognised after a reinstall — phase 5's behaviour, and the reason
     * phase 6 exists.
     */
    val contentHash: String,
    /** The size and modification time [contentHash] was taken against, which is what makes it reusable. */
    val contentSizeBytes: Long,
    val contentModifiedSeconds: Long,
    /** The storage channel this row was sent to; 0 until a send is attempted. */
    val telegramChatId: Long,
    /** The message Telegram created; 0 until [UploadState.BackedUp]. */
    val telegramMessageId: Long,
    /** Send attempts so far, which is what bounds [BackupFailureKind.retryable] loops. */
    val attempts: Int,
    val failure: BackupFailure?,
) {
    /**
     * Whether the recorded hash still describes this file.
     *
     * The fast check of PRD section 12, in one line: two cheap MediaStore figures have to agree with the
     * snapshot the hash was taken against. Agreement is only ever grounds for *not* re-reading the file —
     * a hash is reused from here, never a conclusion about whether the content is backed up, and that
     * question is always answered by the hash itself.
     */
    val identityIsCurrent: Boolean
        get() = contentHash.isNotBlank() &&
            contentSizeBytes == expectedSizeBytes &&
            contentModifiedSeconds == modifiedSeconds
}

/** Counts for the progress line, all derived from the same query so they cannot disagree. */
data class BackupQueueSummary(
    val queued: Int = 0,
    val inFlight: Int = 0,
    val backedUp: Int = 0,
    val failed: Int = 0,
) {
    val pending: Int get() = queued + inFlight
    val isActive: Boolean get() = pending > 0
    val total: Int get() = queued + inFlight + backedUp + failed
}

/** The projection the Photos grid asks for: which items have a state, and what it is. */
data class BackupItemState(
    val mediaStoreId: Long,
    val stateKey: String,
) {
    val state: UploadState get() = UploadState.fromStorageKey(stateKey)
}

/**
 * The queue as Room persists it — the brief's "do not keep the queue only in memory", made concrete
 * by every read and write going through here.
 *
 * Nothing in this interface performs a Telegram call: the queue's job is to remember what is owed and
 * to hand out one item at a time, which is what makes it testable without a network or an account.
 */
interface BackupQueueRepository {
    /**
     * The visible window's states, as a map so a cell lookup is O(1) during a scroll. Bounded by the
     * caller's window rather than by the size of the queue.
     */
    fun observeStatesFor(mediaStoreIds: Collection<Long>): Flow<Map<Long, UploadState>>

    fun observeSummary(): Flow<BackupQueueSummary>

    /**
     * Adds records for items that have none and moves recognised ones into the queue, returning how many
     * are now waiting.
     *
     * An item already in flight, already queued or already `BACKED_UP` is left exactly as it is rather than
     * re-queued: re-sending a photo because the user tapped twice would put a second copy in the channel.
     * Recognition may have got there first and filled in a hash — that record moves from
     * [UploadState.NotBackedUp] to [UploadState.Queued] with its identity intact, which is what lets a
     * queue item be sent without hashing it again.
     */
    suspend fun enqueue(mediaStoreIds: Collection<Long>): Int

    /**
     * The queue's frontier of items whose content identity is unknown or doubtful.
     *
     * [includeWholeLibrary] is the layered check's throttle, and the caller sets it from the remote index
     * rather than from a schedule: an item nobody ever asked about is only worth reading when there are
     * remote manifests still unclaimed, which after a reinstall is all of them and on a settled library is
     * none of them. With the flag off, only items the app already has a record for appear — queued ones,
     * which need a hash before they can be sent, and completed backups, whose file may have been edited
     * since.
     *
     * [limit] bounds one pass. A hundred-thousand-item library is walked a page at a time, oldest-work
     * first, and an item that is not in this page costs no file read at all.
     */
    suspend fun identityCandidates(includeWholeLibrary: Boolean, limit: Int): List<BackupIdentityCandidate>

    /**
     * Records what one file's bytes are.
     *
     * Never changes state, so this can run while a worker owns the row. An empty
     * [MediaIdentity.contentHash] with a non-zero attempt timestamp is the honest record of a file that
     * could not be read: it keeps the item out of the next pass's candidates without ever making it look
     * backed up.
     */
    suspend fun recordIdentity(mediaStoreId: Long, identity: MediaIdentity)

    /**
     * Points a record at a message the remote index says already holds this content.
     *
     * Returns false when the row was in the middle of something else — a `preparing` or `uploading` row
     * belongs to the worker that claimed it, and a scan that rewrote one would either lose the message id
     * that send is about to produce or mark a file backed up while its own bytes are going out again.
     */
    suspend fun adoptRemote(mediaStoreId: Long, remote: RemoteBackup): Boolean

    /**
     * Detaches a record from a backup that no longer describes it, because the file behind it became
     * different content.
     *
     * The Telegram message is left completely alone: this removes an association, not a copy of the user's
     * photo. [UploadState.BackedUp] goes to [UploadState.NotBackedUp], which is the state that means "this
     * content is here and nothing stores it" — eligible for a backup the user asks for, and no ✓.
     */
    suspend fun revokeAssociation(mediaStoreId: Long): Boolean

    /** Whether anything is waiting for a worker, which is what lets a hashing pass yield to an upload. */
    suspend fun hasQueuedWork(): Boolean

    /**
     * Moves the oldest [UploadState.Queued] row to [UploadState.Preparing] and returns it, or null
     * when nothing is left to do. A row whose media has since been pruned from the index is failed
     * here and the next one is taken instead, so a deleted file cannot wedge the queue.
     */
    suspend fun claimNext(chatId: Long): BackupRequest?

    suspend fun markUploading(mediaStoreId: Long)

    /** Records where the staged copy landed, so completion or a later cleanup can remove it. */
    suspend fun markStaged(mediaStoreId: Long, path: String)

    /**
     * Records the message that holds this content.
     *
     * Two things can stand behind that claim, and both are evidence rather than intent: a send Telegram
     * confirmed, or the remote manifest Phase 6 matched before the bytes went out. What may not produce it
     * is a row simply being ready — hence the [UploadState.Queued] rows this refuses.
     */
    suspend fun markBackedUp(mediaStoreId: Long, chatId: Long, messageId: Long)

    /** Where the staged copy for this row lives, so a later cleanup can find it. Empty if none. */
    suspend fun stagedPathOf(mediaStoreId: Long): String

    /**
     * Records a send that did not happen.
     *
     * A retryable [BackupFailure] returns the row to [UploadState.Queued] with its attempt count
     * incremented; once the queue's own cap is reached it becomes [UploadState.Failed] instead, so a
     * permanently refused file cannot loop forever. A non-retryable one fails immediately: waiting
     * three attempts to report "this file is no longer on the device" is not patience.
     */
    suspend fun release(request: BackupRequest, failure: BackupFailure)

    /** Queued rows only — an in-flight send is not interrupted from here. */
    suspend fun cancelQueued(): Int

    /** Puts every [UploadState.Failed] row back in line, for the "Retry failed" affordance. */
    suspend fun requeueFailed(): Int

    /**
     * Rows left in [UploadState.Preparing] or [UploadState.Uploading] by a killed process.
     *
     * They go back to [UploadState.Queued] rather than being called failures: nothing went wrong with
     * the media, the app simply stopped. The row may already exist in Telegram — an interrupted send can
     * be committed server-side — and that is what Phase 6's hash matching exists to catch: the next pass
     * finds the content in the channel's manifest and adopts that message instead of sending it again.
     */
    suspend fun reconcileInterrupted(): Int
}

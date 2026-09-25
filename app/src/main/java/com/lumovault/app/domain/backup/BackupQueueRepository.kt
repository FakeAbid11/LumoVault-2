package com.lumovault.app.domain.backup

import com.lumovault.app.domain.model.MediaType
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
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    val state: UploadState,
    /** The storage channel this row was sent to; 0 until a send is attempted. */
    val telegramChatId: Long,
    /** The message Telegram created; 0 until [UploadState.BackedUp]. */
    val telegramMessageId: Long,
    /** Send attempts so far, which is what bounds [BackupFailureKind.retryable] loops. */
    val attempts: Int,
    val failure: BackupFailure?,
)

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
     * Adds rows for items that have none, returning how many were actually enqueued.
     *
     * An item already in flight or already `BACKED_UP` is left exactly as it is rather than
     * re-queued: re-sending a photo because the user tapped twice would put a second copy in the
     * channel, which Phase 6's deduplication is meant to prevent, not Phase 5.
     */
    suspend fun enqueue(mediaStoreIds: Collection<Long>): Int

    /**
     * Moves the oldest [UploadState.Queued] row to [UploadState.Preparing] and returns it, or null
     * when nothing is left to do. A row whose media has since been pruned from the index is failed
     * here and the next one is taken instead, so a deleted file cannot wedge the queue.
     */
    suspend fun claimNext(chatId: Long): BackupRequest?

    suspend fun markUploading(mediaStoreId: Long)

    /** Records where the staged copy landed, so completion or a later cleanup can remove it. */
    suspend fun markStaged(mediaStoreId: Long, path: String)

    /** Records the message Telegram created; [telegramMessageId] is what Phase 6 will match against. */
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
     * the media, the app simply stopped. The row may already exist in Telegram — an interrupted send
     * can be committed server-side — and that duplicate is Phase 6's hash matching to detect, not
     * something Phase 5 can know about here.
     */
    suspend fun reconcileInterrupted(): Int
}

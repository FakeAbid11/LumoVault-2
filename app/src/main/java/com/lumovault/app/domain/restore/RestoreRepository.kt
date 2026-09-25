package com.lumovault.app.domain.restore

import com.lumovault.app.domain.telegram.DownloadProgress
import kotlinx.coroutines.flow.Flow

/**
 * One restore as the table holds it, in the vocabulary the rest of the app uses.
 *
 * The row is read by two screens at once — the Cloud grid, which has to draw a bar on a cell, and the item
 * sheet, which has to say which of five things is happening — and by the startup sweep that cleans up after
 * a killed process. A model rather than the entity, because those callers should be reasoning about state
 * and progress and not about a string column.
 */
data class RestoreJob(
    val chatId: Long,
    val messageId: Long,
    val state: RestoreState,
    val downloadedBytes: Long,
    val expectedSizeBytes: Long,
    /** TDLib's file id for the cache copy; 0 until the transfer exists. */
    val tdlibFileId: Int,
    /** The local index row this restore produced; 0 until it did. */
    val mediaStoreId: Long,
    val failure: RestoreFailureKind?,
) {
    /**
     * What to draw, or nothing.
     *
     * Null is the answer for an unknown size and for the states where a bar would be a decoration rather
     * than information — which is why a caller gets a nullable fraction here instead of a `0f` that reads as
     * "stuck at zero".
     */
    val progress: DownloadProgress
        get() = DownloadProgress(downloadedBytes, expectedSizeBytes)

    val fraction: Float?
        get() = if (state == RestoreState.Downloading) progress.fraction else null

    val isLive: Boolean
        get() = state.isLive
}

/**
 * The restore table: request a job, record what it is doing, and settle it.
 *
 * Nothing here performs a transfer. That split is the same one the backup queue made in Phase 5, and for
 * the same reason — the state machine is the part with bugs in it, and it is the part that can be tested
 * without TDLib, a network or a file.
 */
interface RestoreRepository {
    /** Live jobs for a window of messages, keyed by message id, so a grid can look one up per cell. */
    fun observeForMessages(chatId: Long, messageIds: Collection<Long>): Flow<Map<Long, RestoreJob>>

    fun observeJob(chatId: Long, messageId: Long): Flow<RestoreJob?>

    /** Jobs in a live state, newest first — the list a screen shows and a sweep cleans up after. */
    fun observeLive(limit: Int): Flow<List<RestoreJob>>

    suspend fun job(chatId: Long, messageId: Long): RestoreJob?

    /**
     * Takes a request, or returns null when one is already running.
     *
     * The caller starts the transfer only when this hands back a job, which is what makes two taps on the
     * same download one download rather than two fighting over the same message.
     */
    suspend fun take(target: CloudRestoreTarget): RestoreJob?

    suspend fun recordDownloadTarget(chatId: Long, messageId: Long, tdlibFileId: Int)

    suspend fun markDownloading(chatId: Long, messageId: Long, downloadedBytes: Long)

    suspend fun markState(chatId: Long, messageId: Long, state: RestoreState)

    suspend fun complete(chatId: Long, messageId: Long, mediaStoreId: Long, contentHash: String, downloadedBytes: Long)

    suspend fun fail(chatId: Long, messageId: Long, failure: RestoreFailureKind)

    /** A job the user asked to stop. Distinct from [fail] because the reason is not a fault. */
    suspend fun cancel(chatId: Long, messageId: Long)

    /**
     * Settles everything a previous process left mid-flight, and returns the TDLib file ids to release.
     *
     * They are reported as interrupted rather than resumed: nothing here re-attaches to a transfer whose
     * coroutine is gone, and a bar that starts moving again after a restart would be a claim about a
     * download that never happened. The ids travel with the call because the caller is the one object that
     * can talk to TDLib about them.
     */
    suspend fun reconcileInterrupted(): List<Int>
}

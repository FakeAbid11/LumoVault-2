package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.MediaSourceStager
import com.lumovault.app.domain.backup.StagedSource
import com.lumovault.app.domain.backup.TelegramUploadRepository
import com.lumovault.app.domain.backup.UploadEvent
import com.lumovault.app.domain.backup.UploadRequest
import kotlin.coroutines.currentCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.transform

/** What is happening to the item being sent right now, for whoever is showing progress. */
data class BackupProgress(val mediaStoreId: Long, val displayName: String, val fraction: Float)

/**
 * What a pass over the queue ended with.
 *
 * [NoChannel] is not a failure of any item: it means nothing was attempted, because uploading into an
 * arbitrary chat to keep a queue moving is the one thing this engine must never do.
 *
 * [deferred] means a retryable item is still waiting. The pass stops rather than trying it again
 * immediately — retrying a network failure in a tight loop is not backoff, it is a way to turn one
 * dropped connection into a hundred, and on mobile data it costs the user money.
 */
sealed interface QueueRun {
    data class Done(val sent: Int, val failed: Int, val deferred: Boolean) : QueueRun
    data object NoChannel : QueueRun
    data object TelegramUnavailable : QueueRun
}

/**
 * Drains the backup queue: claim one item, put its bytes where TDLib can read them, send them as the
 * message type the media actually is, and record what Telegram made.
 *
 * This is the second part of LumoVault that genuinely spans more than one collaborator — Room for what
 * is owed, MediaStore for the bytes, Telegram for the copy — so it belongs here rather than in the
 * worker that triggers it, or in a repository that would have to pretend to know about the others.
 *
 * Four rules it exists to hold:
 * - One item at a time. TDLib uploads as part of sending, so concurrent sends mean concurrent file
 *   streams and memory spikes on exactly the devices this app targets. Reliability before throughput.
 * - Every outcome writes a state, including the failures that happen before Telegram is involved. A row
 *   left in `PREPARING` because staging failed would disappear from the summary and be reported as
 *   nothing at all.
 * - A failure belongs to the item that failed. In a queue of five where one file was deleted, four
 *   back up and one is reported — not zero.
 * - Nothing is attempted twice in one pass.
 */
class RunBackupQueueUseCase(
    private val queue: BackupQueueRepository,
    private val upload: TelegramUploadRepository,
    private val stager: MediaSourceStager,
    private val resolveChannel: suspend () -> Long,
) {
    suspend fun run(onProgress: suspend (BackupProgress) -> Unit = {}): QueueRun {
        if (!upload.isUsable) return QueueRun.TelegramUnavailable

        // Rows left mid-flight by a killed process are owed work again, not evidence of failure.
        queue.reconcileInterrupted()

        val chatId = resolveChannel()
        if (chatId == NO_CHANNEL) return QueueRun.NoChannel

        var sent = 0
        var failed = 0
        // Bounded by the queue itself: one entry per item this pass was refused, which is also the
        // worst case for how long the loop can run.
        val deferred = mutableSetOf<Long>()

        while (true) {
            currentCoroutineContext().ensureActive()
            val request = queue.claimNext(chatId) ?: break

            if (request.mediaStoreId in deferred) {
                // The oldest waiting item is one we already refused. Everything behind it would be
                // refused in turn, so the pass ends here and the caller decides when to try again.
                break
            }

            if (send(chatId, request, onProgress)) sent++ else {
                failed++
                deferred += request.mediaStoreId
            }
        }

        return QueueRun.Done(sent, failed, deferred = deferred.isNotEmpty())
    }

    /** Withdraws everything still waiting. An in-flight send is left to finish on its own. */
    suspend fun cancelPending(): Int = queue.cancelQueued()

    /** Puts every failed item back in line, for the retry affordance rather than an automatic loop. */
    suspend fun retryFailed(): Int = queue.requeueFailed()

    private suspend fun send(
        chatId: Long,
        request: BackupRequest,
        onProgress: suspend (BackupProgress) -> Unit,
    ): Boolean {
        val staged = stager.stage(request.contentUri, request.displayName)

        if (staged is StagedSource.Unavailable) {
            queue.release(request, staged.failure)
            return false
        }

        queue.markStaged(request.mediaStoreId, staged.path)
        queue.markUploading(request.mediaStoreId)

        val terminal = try {
            upload.upload(chatId, request.toUpload(staged))
                .transform { event ->
                    // Progress is forwarded and dropped; the last event decides the row's state, so it
                    // has to be the only thing that survives the flow.
                    if (event is UploadEvent.Progress) {
                        onProgress(
                            BackupProgress(
                                mediaStoreId = request.mediaStoreId,
                                displayName = request.displayName,
                                fraction = event.fraction,
                            ),
                        )
                    } else {
                        emit(event)
                    }
                }
                .lastOrNull()
        } catch (cancelled: CancellationException) {
            // The row stays in flight and the next pass reconciles it. The copy belongs to this
            // process, so it goes now rather than waiting for the start-up sweep.
            stager.discard(staged.path)
            throw cancelled
        }

        stager.discard(staged.path)

        return when (terminal) {
            is UploadEvent.Sent -> {
                queue.markBackedUp(request.mediaStoreId, terminal.chatId, terminal.messageId)
                true
            }

            is UploadEvent.Refused -> {
                queue.release(request, terminal.failure)
                false
            }

            // No terminal event means the upload answered nothing at all. That is a broken
            // collaborator rather than a Telegram condition, so the bounded retry is the honest
            // response — and the row must not be left claiming it is still uploading.
            null -> {
                queue.release(request, BackupFailure(BackupFailureKind.Unknown))
                false
            }
        }
    }

    private companion object {
        /** TDLib reserves 0 for "no identifier", so a channel id of 0 means none was ever adopted. */
        const val NO_CHANNEL = 0L
    }
}

private fun BackupRequest.toUpload(staged: StagedSource.Ready) = UploadRequest(
    mediaStoreId = mediaStoreId,
    mediaType = mediaType,
    mimeType = mimeType,
    stagedPath = staged.path,
    // Counted while copying, not read from the index: this is the number of bytes about to be sent.
    sizeBytes = staged.sizeBytes,
    displayName = displayName,
    width = width,
    height = height,
    durationMillis = durationMillis,
)

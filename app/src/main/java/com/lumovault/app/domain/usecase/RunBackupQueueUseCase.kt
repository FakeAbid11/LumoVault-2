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
import com.lumovault.app.domain.telegram.BackupManifest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.lastOrNull
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
    data class Done(
        val sent: Int,
        val failed: Int,
        val deferred: Boolean,
        /** Items this pass found already stored and closed without sending — Phase 6's whole point. */
        val deduplicated: Int = 0,
    ) : QueueRun
    data object NoChannel : QueueRun
    data object TelegramUnavailable : QueueRun
}

/** What became of one claimed item. A failure carries its kind, because [QueueRun.Done.deferred] is a promise about retryable work only. */
private sealed interface ItemRun {
    data object Sent : ItemRun
    data object Deduplicated : ItemRun
    data class Failed(val kind: BackupFailureKind) : ItemRun
}

/**
 * Drains the backup queue: claim one item, put its bytes where TDLib can read them, send them as the
 * message type the media actually is, and record what Telegram made.
 *
 * This is the second part of LumoVault that genuinely spans more than one collaborator — Room for what
 * is owed, MediaStore for the bytes, Telegram for the copy — so it belongs here rather than in the
 * worker that triggers it, or in a repository that would have to pretend to know about the others.
 *
 * Five rules it exists to hold:
 * - One item at a time. TDLib uploads as part of sending, so concurrent sends mean concurrent file
 *   streams and memory spikes on exactly the devices this app targets. Reliability before throughput.
 * - Every outcome writes a state, including the failures that happen before Telegram is involved. A row
 *   left in `PREPARING` because staging failed would disappear from the summary and be reported as
 *   nothing at all.
 * - A failure belongs to the item that failed. In a queue of five where one file was deleted, four
 *   back up and one is reported — not zero.
 * - Nothing is attempted twice in one pass.
 * - Nothing is sent before its content is identified, and an item whose content the channel already holds
 *   is closed against that message rather than uploaded again. Phase 5 could not know this; Phase 6 is
 *   the phase that does, and a duplicate in the user's own library is the failure it exists to prevent.
 */
class RunBackupQueueUseCase(
    private val queue: BackupQueueRepository,
    private val upload: TelegramUploadRepository,
    private val stager: MediaSourceStager,
    private val recognition: RecognizeBackupUseCase,
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
        var deduplicated = 0
        // Bounded by the queue itself: one entry per item this pass was refused, which is also the
        // worst case for how long the loop can run.
        val deferred = mutableSetOf<Long>()
        // Only a *retryable* refusal may raise [QueueRun.Done.deferred]: the flag is the worker's
        // signal to ask WorkManager for another pass, and a pass over items that failed because the
        // file is gone or the space is not there cannot make progress — scheduling one is a loop with
        // backoff instead of an answer.
        var awaitingRetry = false

        while (true) {
            coroutineContext.ensureActive()
            val request = queue.claimNext(chatId) ?: break

            if (request.mediaStoreId in deferred) {
                // The oldest waiting item is one we already refused. Everything behind it would be
                // refused in turn, so the pass ends here and the caller decides when to try again.
                break
            }

            when (val outcome = send(chatId, request, onProgress)) {
                ItemRun.Sent -> sent++
                ItemRun.Deduplicated -> deduplicated++
                is ItemRun.Failed -> {
                    failed++
                    deferred += request.mediaStoreId
                    if (outcome.kind.retryable) awaitingRetry = true
                }
            }
        }

        return QueueRun.Done(sent, failed, deferred = awaitingRetry, deduplicated = deduplicated)
    }

    /** Withdraws everything still waiting. An in-flight send is left to finish on its own. */
    suspend fun cancelPending(): Int = queue.cancelQueued()

    /** Puts every failed item back in line, for the retry affordance rather than an automatic loop. */
    suspend fun retryFailed(): Int = queue.requeueFailed()

    private suspend fun send(
        chatId: Long,
        request: BackupRequest,
        onProgress: suspend (BackupProgress) -> Unit,
    ): ItemRun {
        // A `when` over the sealed cases rather than an `if (x !is Ready)` guard: the exhaustive form is
        // the one whose branch types the compiler refines, and the unavailable branch is `Nothing`, so
        // the expression's type is the ready one with no cast.
        val staged = when (val source = stager.stage(request.contentUri, request.displayName)) {
            is StagedSource.Unavailable -> {
                queue.release(request, source.failure)
                return ItemRun.Failed(source.failure.kind)
            }

            is StagedSource.Ready -> source
        }

        queue.markStaged(request.mediaStoreId, staged.path)

        // A copy this process made is this process's to delete — including on the paths where something
        // threw instead of deciding. `purgeStale` at start-up is a backstop, not a plan: without this the
        // worker carries a full-size duplicate of every file it touched until the app is next restarted,
        // and on a phone that is the difference between a retry and an out-of-storage device.
        return try {
            settleStaged(staged, request, chatId, onProgress)
        } finally {
            stager.discard(staged.path)
        }
    }

    private suspend fun settleStaged(
        staged: StagedSource.Ready,
        request: BackupRequest,
        chatId: Long,
        onProgress: suspend (BackupProgress) -> Unit,
    ): ItemRun {
        // Nothing is sent before its content is identified. This is the step that answers the question a
        // queue cannot ask on its own — "is the thing you are about to upload already in the channel?" —
        // and it is asked after staging rather than before so that the hash and the bytes belong to the
        // same file: the copy on disk is the thing Telegram is about to receive.
        val manifest = when (
            val identity = recognition.resolveForUpload(request, staged.path, staged.sizeBytes)
        ) {
            is UploadIdentity.Unreadable -> {
                queue.release(request, identity.failure)
                return ItemRun.Failed(identity.failure.kind)
            }

            is UploadIdentity.AlreadyStored -> {
                // The copy was never needed, and the row closes against the message that already holds
                // this content. `markBackedUp` takes its message id from the remote index, so the ✓ that
                // appears on this thumbnail is a fact about the user's channel rather than an assumption
                // about a send.
                queue.markBackedUp(request.mediaStoreId, identity.remote.chatId, identity.remote.messageId)
                return ItemRun.Deduplicated
            }

            is UploadIdentity.Sendable -> identity.manifest
        }

        queue.markUploading(request.mediaStoreId)

        // On cancellation the row is left in flight for the next pass to reconcile, and the staged copy
        // goes in the caller's `finally` like every other exit.
        val terminal = upload.upload(chatId, request.toUpload(staged, manifest))
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

        return when (terminal) {
            is UploadEvent.Sent -> {
                queue.markBackedUp(request.mediaStoreId, terminal.chatId, terminal.messageId)
                ItemRun.Sent
            }

            is UploadEvent.Refused -> {
                queue.release(request, terminal.failure)
                ItemRun.Failed(terminal.failure.kind)
            }

            // Nothing terminal came back — either the flow ended silently or it stopped on a progress
            // event. Either way the row must not be left claiming it is still uploading, and "unknown"
            // is the honest answer rather than a failure invented on its behalf.
            null, is UploadEvent.Progress -> {
                queue.release(request, BackupFailure(BackupFailureKind.Unknown))
                ItemRun.Failed(BackupFailureKind.Unknown)
            }
        }
    }

    private companion object {
        /** TDLib reserves 0 for "no identifier", so a channel id of 0 means none was ever adopted. */
        const val NO_CHANNEL = 0L
    }
}

private fun BackupRequest.toUpload(staged: StagedSource.Ready, manifest: BackupManifest) = UploadRequest(
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
    manifest = manifest,
)

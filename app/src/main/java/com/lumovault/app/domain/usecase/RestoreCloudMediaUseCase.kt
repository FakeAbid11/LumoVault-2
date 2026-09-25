package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.ContentDigest
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.restore.CloudRestoreTarget
import com.lumovault.app.domain.restore.RestorableSource
import com.lumovault.app.domain.restore.RestoreFailure
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreOutcome
import com.lumovault.app.domain.restore.RestoreRepository
import com.lumovault.app.domain.restore.RestoreState
import com.lumovault.app.domain.restore.RestoredMediaWriter
import com.lumovault.app.domain.restore.StoredMedia
import com.lumovault.app.domain.telegram.OriginalDownload
import com.lumovault.app.domain.telegram.RemoteOriginal
import com.lumovault.app.domain.telegram.TelegramOriginalRepository
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Takes a cloud record back to the device, and records it as the backup it already is.
 *
 * PRD section 52's flow, in one place, because the steps are only correct in this order:
 *
 * ```
 * already here? → room to spare? → claim the job → download → hash what landed
 *   → file into MediaStore → release TDLib's copy → scan → settle the queue row
 * ```
 *
 * Two of those are the reason this is a use case and not a method on a repository: the scan and the queue
 * write have to happen after the file exists, and skipping either produces a bug the user can see. Without
 * the scan, a restored photo is invisible to the library until something else refreshes it. Without the
 * queue write it is a brand-new item with a new hash, and Phase 6 — correctly, on its own evidence — offers
 * it for upload, so the user's channel gains a second copy of the picture they just downloaded.
 *
 * The hash recorded here is of the bytes that arrived, never the manifest's. Telegram stores photos and
 * videos in containers of its own choosing, so the two differ routinely, and writing the manifest's value
 * would assert a byte-for-byte match this app has never measured. What is asserted instead is narrower and
 * true: this content is in that message, and this file is it.
 */
class RestoreCloudMediaUseCase(
    private val restores: RestoreRepository,
    private val downloads: TelegramOriginalRepository,
    private val writer: RestoredMediaWriter,
    private val queue: BackupQueueRepository,
    private val media: MediaRepository,
    private val hasher: MediaContentHasher,
    /** The application's scope, so a download the user started is not cancelled by a thumb moving on. */
    private val scope: CoroutineScope,
) {
    private val running = ConcurrentHashMap<Pair<Long, Long>, Job>()

    /**
     * Begins a restore and returns immediately.
     *
     * Progress is not the caller's to hold: it lives in the job row, which is what lets the Cloud grid and
     * the item sheet draw the same bar, and what keeps the transfer running when neither is on screen.
     */
    fun start(target: CloudRestoreTarget) {
        scope.launch { restore(target) }
    }

    /**
     * Runs one restore to its end. Exposed for the reason the queue runner's `run` is: a retry after a
     * restart, and a test, both need the body without the launching.
     */
    suspend fun restore(target: CloudRestoreTarget): RestoreOutcome {
        if (!target.isRestorable) {
            return RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.SourceGone))
        }
        if (!downloads.isUsable) {
            return RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.NotAuthenticated))
        }

        val remote = RemoteBackup(target.chatId, target.messageId)

        // The duplicate check that matters most is the one before any bytes move. Both answers come from
        // Phase 6's own record: a queue row naming this message, or one whose hash is the manifest's.
        queue.residentBackupFor(remote, target.manifestHash)?.let { resident ->
            // A live row has to be closed here: leaving it would draw a progress bar on a transfer that
            // will never advance, and write nothing is the one state a job row must not sit in. No hash is
            // recorded, because nothing was read.
            restores.job(target.chatId, target.messageId)
                ?.takeIf { it.state.isLive }
                ?.let { live ->
                    restores.complete(target.chatId, target.messageId, resident, "", live.downloadedBytes)
                }
            return RestoreOutcome.AlreadyOnDevice(resident)
        }

        if (!writer.hasRoomFor(target.expectedSizeBytes)) {
            return RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.InsufficientSpace))
        }

        val job = restores.take(target) ?: return RestoreOutcome.InProgress

        // Registered under the job that is running this call, and with putIfAbsent rather than a put: two
        // taps in the same frame both reach here, and the second has to learn it did not win instead of
        // starting a second transfer for one message.
        val owner = coroutineContext[Job] ?: return RestoreOutcome.InProgress
        if (running.putIfAbsent(target.chatId to target.messageId, owner) != null) {
            return RestoreOutcome.InProgress
        }

        return try {
            transfer(target, job, remote)
        } catch (cancelled: CancellationException) {
            // The download layer has already told TDLib to stop. What is left is the honest state.
            restores.cancel(target.chatId, target.messageId)
            throw cancelled
        } finally {
            running.remove(target.chatId to target.messageId, owner)
        }
    }

    /**
     * Stops a transfer this process is running, or says it is not running one.
     *
     * False after a restart is the correct answer, not a failure to cancel: nothing is in flight, and the
     * row that looks like it was has already been settled by [reconcileAfterStart].
     */
    fun cancel(chatId: Long, messageId: Long): Boolean {
        val job = running.remove(chatId to messageId) ?: return false
        job.cancel()
        return true
    }

    /**
     * `containsKey` spelled out rather than `in`: on a `ConcurrentHashMap` the operator resolves to the
     * Java `contains` method — which is `containsValue` — and the compiler rejects the ambiguity as an
     * error rather than letting a liveness check answer a question about values.
     */
    fun isRunning(chatId: Long, messageId: Long): Boolean = running.containsKey(chatId to messageId)

    /**
     * Releases what a killed process left in TDLib's cache and settles the rows describing it.
     *
     * Called once at start-up, before anything can draw a progress bar, so the first frame the user sees is
     * truthful about what is downloading.
     */
    suspend fun reconcileAfterStart() {
        val abandoned = restores.reconcileInterrupted()
        if (abandoned.isEmpty() || !downloads.isUsable) return
        // An id with no path is all `release` needs: the file is inside TDLib's own directory, and asking
        // TDLib to drop its reference is the only way to remove it that leaves TDLib's records consistent.
        abandoned.forEach { fileId -> downloads.release(OriginalDownload.Ready(fileId, "", 0L)) }
    }

    private suspend fun transfer(
        target: CloudRestoreTarget,
        job: RestoreJob,
        remote: RemoteBackup,
    ): RestoreOutcome {
        val downloaded = downloads.download(
            RemoteOriginal(target.remoteFileId, target.mediaType),
        ) { progress ->
            restores.markDownloading(target.chatId, target.messageId, progress.downloadedBytes)
        }

        val ready = when (downloaded) {
            is OriginalDownload.Failed -> {
                restores.fail(target.chatId, target.messageId, downloaded.failure.kind)
                return RestoreOutcome.Refused(downloaded.failure)
            }

            is OriginalDownload.Ready -> downloaded
        }

        restores.recordDownloadTarget(target.chatId, target.messageId, ready.fileId)
        restores.markState(target.chatId, target.messageId, RestoreState.Verifying)

        // Verification, in the only form the facts support: TDLib reported the transfer complete, the file
        // is there, and hashing the whole thing came back with that same length. A hash that disagrees with
        // the file's own size means the bytes changed while they were being read — which is not a restore.
        val digest = hasher.hashFile(ready.path)
        val computed = digest as? ContentDigest.Computed
        if (computed == null || computed.hash.isBlank() || computed.sizeBytes != ready.sizeBytes) {
            downloads.release(ready)
            restores.fail(target.chatId, target.messageId, RestoreFailureKind.Incomplete)
            return RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.Incomplete))
        }

        restores.markState(target.chatId, target.messageId, RestoreState.Saving)

        val stored = writer.store(
            RestorableSource(
                path = ready.path,
                preferredName = target.displayName,
                declaredMimeType = target.mimeType,
                mediaType = target.mediaType,
            ),
        )
        // Released either way. Once the bytes are in MediaStore — or once that has failed — TDLib's copy is
        // a duplicate of something the user either owns properly or does not own at all.
        downloads.release(ready)

        return when (stored) {
            is StoredMedia.Refused -> {
                restores.fail(target.chatId, target.messageId, stored.failure.kind)
                RestoreOutcome.Refused(stored.failure)
            }

            is StoredMedia.Ready -> settle(target, job, remote, stored, computed.hash)
        }
    }

    private suspend fun settle(
        target: CloudRestoreTarget,
        job: RestoreJob,
        remote: RemoteBackup,
        stored: StoredMedia.Ready,
        contentHash: String,
    ): RestoreOutcome {
        media.sync()
        val local = media.local(stored.mediaStoreId)
        val identical = target.manifestHash.matches(contentHash)

        // A restored file the index has not seen is still a restored file, and saying so is the honest
        // version of it. The queue write waits, because a backup record keyed to an id the library does not
        // hold is how a phantom ✓ gets drawn.
        if (local == null) {
            restores.complete(target.chatId, target.messageId, stored.mediaStoreId, contentHash, job.downloadedBytes)
            return RestoreOutcome.Restored(
                mediaStoreId = stored.mediaStoreId,
                contentHash = contentHash,
                byteIdenticalToUpload = identical,
                recordedAsBackedUp = false,
                indexed = false,
            )
        }

        val recorded = queue.recordRestored(
            mediaStoreId = local.id,
            remote = remote,
            identity = MediaIdentity(
                contentHash = contentHash,
                observedSizeBytes = local.sizeBytes,
                observedModifiedSeconds = local.dateModifiedSeconds,
            ),
        )
        restores.complete(target.chatId, target.messageId, local.id, contentHash, job.downloadedBytes)

        return RestoreOutcome.Restored(
            mediaStoreId = local.id,
            contentHash = contentHash,
            byteIdenticalToUpload = identical,
            recordedAsBackedUp = recorded,
            indexed = true,
        )
    }

    /**
     * Three answers, not two.
     *
     * "No manifest to compare against" is a different fact from "a manifest that did not match", and the
     * second is the one that means Telegram stored its own re-encode. Only the second deserves the words
     * the UI uses; the first deserves silence, because a pre-Phase 6 upload cannot be judged.
     */
    private fun String.matches(landed: String): Boolean? =
        takeIf { isNotBlank() }?.let { equals(landed, ignoreCase = true) }
}

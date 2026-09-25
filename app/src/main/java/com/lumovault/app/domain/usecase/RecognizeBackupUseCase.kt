package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupIdentityCandidate
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.ContentDigest
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.telegram.BackupManifest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** What one recognition pass got done. */
data class RecognitionRun(
    val considered: Int = 0,
    val hashed: Int = 0,
    /** Items whose content the channel already held, now marked backed up without an upload. */
    val adopted: Int = 0,
    /** Items whose file became different content, so their stored association was dropped. */
    val revoked: Int = 0,
    val unreadable: Int = 0,
    /** True when work remains but this pass yielded — to the queue, or to its own time budget. */
    val stoppedEarly: Boolean = false,
)

/**
 * What the queue is allowed to do with one item it has staged.
 *
 * [AlreadyStored] is the outcome this whole phase exists to produce: the bytes are in the channel
 * already, so nothing is sent, and the row finishes as backed up pointing at the message that holds them.
 */
sealed interface UploadIdentity {
    data class AlreadyStored(val remote: RemoteBackup) : UploadIdentity

    /** These exact bytes are not stored anywhere; [manifest] is what the new message should declare. */
    data class Sendable(val manifest: BackupManifest) : UploadIdentity

    data class Unreadable(val failure: BackupFailure) : UploadIdentity
}

/**
 * Works out what local content *is*, and whether Telegram already holds it.
 *
 * This is PRD section 12's layered approach as an executable rule. The cheap layer is a comparison of two
 * numbers the media index already carries — size and modification time — against the pair recorded with
 * the last hash; a match means the file is passed over without being read at all, which on a settled
 * library means the library is never re-hashed. Only a mismatch, or an item never yet identified, reaches
 * the expensive layer: one streamed SHA-256, and then a lookup of that hash in the remote index. The
 * order matters the other way too — a size and timestamp match is never grounds for calling something
 * backed up, only for not re-measuring it.
 *
 * Two things bound the expensive layer, because neither the UI nor the upload queue may wait on it:
 * a pass yields as soon as anything is queued to send (uploading is what the user asked for; recognition
 * is background tidying), and a pass stops at its own deadline rather than running until the frontier is
 * empty.
 *
 * And one thing colours every write here: an item that cannot be hashed is recorded as *attempted with no
 * hash*, which keeps it out of the next pass without ever making it look stored. PRD section 18's rule —
 * a failure must not corrupt backup state — is satisfied by the shape of the record rather than by
 * checking for it at each call site.
 */
class RecognizeBackupUseCase(
    private val queue: BackupQueueRepository,
    private val cloud: CloudIndexRepository,
    private val hasher: MediaContentHasher,
    /** Injectable so a test can control the deadline without sleeping. */
    private val nanoTime: () -> Long = System::nanoTime,
    private val candidatesPerStage: Int = CANDIDATES_PER_STAGE,
    private val maxMillisPerRun: Long = MAX_MILLIS_PER_RUN,
) {
    /**
     * One bounded pass over whatever needs identifying.
     *
     * Loops rather than doing a single page, because a page of 20 photos is a fraction of a second and
     * stopping there would stretch a reinstall's 1,000 recognitions across fifty passes. The deadline and
     * the preemption check are what stop the loop instead of a count.
     */
    suspend fun run(): RecognitionRun {
        val deadline = nanoTime() + maxMillisPerRun * MILLIS_TO_NANOS

        var tally = RecognitionRun()
        while (true) {
            coroutineContext.ensureActive()

            if (queue.hasQueuedWork()) return tally.copy(stoppedEarly = true)
            if (nanoTime() >= deadline) return tally.copy(stoppedEarly = true)

            // Reading the library at all is only worth it while the channel holds manifests no local
            // record claims. At zero, hashing cannot find anything new and a hundred thousand files cost
            // no reads; after a reinstall that figure is the size of the library, and it is the work.
            val candidates = queue.identityCandidates(
                includeWholeLibrary = cloud.unrecognizedRemoteCount() > 0,
                limit = candidatesPerStage,
            )
            if (candidates.isEmpty()) return tally

            for (candidate in candidates) {
                coroutineContext.ensureActive()
                val outcome = recognise(candidate)
                tally = tally.copy(
                    considered = tally.considered + 1,
                    hashed = tally.hashed + outcome.hashedCount,
                    adopted = tally.adopted + outcome.adoptedCount,
                    revoked = tally.revoked + outcome.revokedCount,
                    unreadable = tally.unreadable + outcome.unreadableCount,
                )
            }
        }
    }

    /**
     * Identifies one item the queue has already staged, and answers the only question the upload path has:
     * do these bytes need sending?
     *
     * [stagedSizeBytes] is the byte count of the copy about to be sent, which is what the manifest records
     * alongside the hash — both taken from the same file, so a caption can never describe content that is
     * not in the message carrying it. The hash of that copy is computed here rather than reusing a
     * MediaStore read for the same reason: an item being uploaded is being read anyway, and hashing the
     * staged bytes costs one extra pass over a file already on disk instead of a second trip through a
     * content provider.
     *
     * A recorded hash is reused without recomputing it when the fast check still holds
     * ([BackupRequest.identityIsCurrent]) *and* the staged copy has the size that check was made against —
     * the second half of that condition is what stops a file that changed after the index was read from
     * being sent under an out-of-date manifest.
     */
    suspend fun resolveForUpload(
        request: BackupRequest,
        stagedPath: String,
        stagedSizeBytes: Long,
    ): UploadIdentity {
        val reusable = request.identityIsCurrent && stagedSizeBytes == request.expectedSizeBytes
        val hash = if (reusable) {
            request.contentHash
        } else {
            when (val digest = hashFile(stagedPath)) {
                is ContentDigest.Unavailable -> return UploadIdentity.Unreadable(digest.failure)
                is ContentDigest.Computed -> digest.hex.also {
                    // The snapshot recorded is the index's own pair, because that is the pair the next
                    // fast check compares against. Recording the byte count from the stream instead would
                    // never match a provider that declines to state a length, and the item would be
                    // re-hashed on every pass forever.
                    queue.recordIdentity(
                        request.mediaStoreId,
                        MediaIdentity(
                            contentHash = it,
                            observedSizeBytes = request.expectedSizeBytes,
                            observedModifiedSeconds = request.modifiedSeconds,
                        ),
                    )
                }
            }
        }

        // Reporting rather than writing: the row is the queue's own claim, so the caller that holds it
        // settles it. Both ends of that call use the same evidence — a message id from the channel — which
        // is the only thing that has ever been allowed to produce a ✓.
        val remote = cloud.remoteBackupFor(hash)
        if (remote != null) return UploadIdentity.AlreadyStored(remote)

        return UploadIdentity.Sendable(
            BackupManifest(
                contentHash = hash,
                sizeBytes = stagedSizeBytes,
                modifiedSeconds = request.modifiedSeconds,
                fileName = request.displayName,
            ),
        )
    }

    private suspend fun recognise(candidate: BackupIdentityCandidate): Outcome =
        when (val digest = hash(candidate.contentUri)) {
            is ContentDigest.Unavailable -> {
                queue.recordIdentity(
                    candidate.mediaStoreId,
                    MediaIdentity.unreadable(
                        observedSizeBytes = candidate.sizeBytes,
                        observedModifiedSeconds = candidate.modifiedSeconds,
                    ),
                )
                Outcome(unreadableCount = 1)
            }

            is ContentDigest.Computed -> settle(candidate, digest.hex)
        }

    private suspend fun settle(candidate: BackupIdentityCandidate, hash: String): Outcome {
        queue.recordIdentity(
            candidate.mediaStoreId,
            MediaIdentity(
                contentHash = hash,
                observedSizeBytes = candidate.sizeBytes,
                observedModifiedSeconds = candidate.modifiedSeconds,
            ),
        )

        val remote = cloud.remoteBackupFor(hash)
        if (remote != null) {
            return if (queue.adoptRemote(candidate.mediaStoreId, remote)) {
                Outcome(hashedCount = 1, adoptedCount = 1)
            } else {
                // Nothing was adopted: the row belongs to a send in flight, which is the one state a scan
                // must not settle. That upload writes its own outcome, with this hash in its caption.
                Outcome(hashedCount = 1)
            }
        }

        val becameDifferentContent = candidate.knownHash.isNotBlank() &&
            candidate.knownHash != hash &&
            candidate.state == UploadState.BackedUp
        if (becameDifferentContent && queue.revokeAssociation(candidate.mediaStoreId)) {
            return Outcome(hashedCount = 1, revokedCount = 1)
        }
        return Outcome(hashedCount = 1)
    }

    /** Counts for one item, so [run] can add up without this class knowing what a summary looks like. */
    private data class Outcome(
        val hashedCount: Int = 0,
        val adoptedCount: Int = 0,
        val revokedCount: Int = 0,
        val unreadableCount: Int = 0,
    )

    private suspend fun hash(contentUri: String): ContentDigest = try {
        hasher.hash(contentUri)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // A content provider can throw IllegalArgumentException for a row that stopped resolving
        // mid-scan. One unreadable file must not end a pass over the other ninety-nine thousand, and "no
        // hash" is the only honest answer either way.
        ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
    }

    private suspend fun hashFile(path: String): ContentDigest = try {
        hasher.hashFile(path)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
    }

    private companion object {
        /**
         * Small on purpose. The pass loops, so this is only how many rows are read from Room at a time and
         * how often the deadline and the queue are checked — twenty items is a few hundred milliseconds of
         * photos between two chances to yield to an upload.
         */
        const val CANDIDATES_PER_STAGE = 20

        /**
         * Five minutes, inside a foreground worker that exists to run for as long as an upload takes. Long
         * enough to recognise a thousand photos in one pass, short enough that a queue which fills while
         * nothing is queued still gets drained by the next trigger.
         */
        const val MAX_MILLIS_PER_RUN = 5L * 60 * 1000

        const val MILLIS_TO_NANOS = 1_000_000L
    }
}

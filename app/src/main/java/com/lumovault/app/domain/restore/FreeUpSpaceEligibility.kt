package com.lumovault.app.domain.restore

import com.lumovault.app.domain.backup.UploadState

/**
 * What is known about one item at the moment a deletion is being considered.
 *
 * Deliberately a *statement of evidence* rather than a boolean, because the phase requires a skipped item
 * to be explained: two items can both be ineligible for reasons that deserve different sentences — one is
 * mid-upload, the other lost its cloud record — and a screen that says "cannot be freed" about both has
 * told the user nothing.
 */
data class ItemEvidence(
    val candidate: FreeUpSpaceCandidate,
    /** False when the index no longer holds the row the user selected. */
    val inIndex: Boolean = true,
    val queueState: UploadState?,
    val telegramChatId: Long,
    val telegramMessageId: Long,
    val hasContentHash: Boolean,
    /** Whether the cloud index still holds the message the queue row names. */
    val cloudMessagePresent: Boolean,
    val trashed: Boolean,
)

/**
 * The rule, in one pure function.
 *
 * This is the only place in the app that decides whether a file may be removed from a phone because a copy
 * exists elsewhere, so it is written to be readable at once and to be testable without a database: every
 * answer comes from evidence the caller had to produce, and the default is refusal.
 *
 * The order matters where two refusals could apply to one item. Trash is checked first because an item the
 * user has already marked for removal should be reported as that, not as a backup question; an in-flight
 * upload is checked before the association columns because a row in `preparing` may not have its message id
 * written yet, and "not backed up" would be the wrong thing to say about work that is happening.
 */
object FreeUpSpaceEligibility {

    /** Null means the item may be deleted. Anything else is why it may not. */
    fun reasonFor(evidence: ItemEvidence): RejectionReason? = when {
        !evidence.inIndex -> RejectionReason.MissingFromIndex

        evidence.trashed -> RejectionReason.InTrash

        evidence.queueState == UploadState.Preparing || evidence.queueState == UploadState.Uploading ->
            RejectionReason.UploadInFlight

        evidence.queueState != UploadState.BackedUp -> RejectionReason.NotBackedUp

        evidence.telegramChatId == 0L || evidence.telegramMessageId == 0L -> RejectionReason.NoAssociation

        !evidence.hasContentHash -> RejectionReason.NoContentIdentity

        !evidence.cloudMessagePresent -> RejectionReason.CloudRecordGone

        else -> null
    }

    fun isDeletable(evidence: ItemEvidence): Boolean = reasonFor(evidence) == null

    /**
     * The items that survived the re-check, with the refusals kept alongside.
     *
     * Takes the requested ids rather than the evidence list, because an id the user selected that is no
     * longer in the index produces no row at all — and that is exactly the case a silent filter would drop
     * without ever telling anyone the file had already gone.
     */
    fun evaluate(requested: Collection<Long>, evidence: Map<Long, ItemEvidence>): List<EligibilityCheck> =
        requested.map { id ->
            val found = evidence[id]
            if (found == null) {
                EligibilityCheck(mediaStoreId = id, candidate = null, rejected = RejectionReason.MissingFromIndex)
            } else {
                EligibilityCheck(id, found.candidate, reasonFor(found))
            }
        }
}

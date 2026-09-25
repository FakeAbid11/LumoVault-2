package com.lumovault.app.domain.restore

import com.lumovault.app.domain.model.MediaType

/**
 * What "Free Up Space" means here, and what it is allowed to touch.
 *
 * The whole feature rests on one distinction, stated in PRD section 53 and worth repeating because the
 * wrong side of it deletes somebody's only copy of a photo:
 *
 * ```
 * ✓ Safely backed up   = a queue row in backed_up, naming a chat and a message, carrying a content hash,
 *                        for a file that is still on this device, where that message is still in the
 *                        cloud index this app read from Telegram.
 * ☁ Exists remotely    = anything else, including a message this app matched by name and size, a send
 *                        that never confirmed, and a file whose hash was never taken.
 * ```
 *
 * Nothing in this file infers the first from the second. A Telegram message is not proof that the bytes it
 * holds are the bytes on the phone, and the four conditions above are the only thing that is.
 */
data class FreeUpSpacePlan(val eligibleCount: Int, val reclaimableBytes: Long)

/** One item the user is being offered to remove, with the figures the screen prints. */
data class FreeUpSpaceCandidate(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mediaType: MediaType,
)

/**
 * Why an item that was eligible a moment ago is not now.
 *
 * §6 of the phase asks for skipped items to be *explained*, which is why this is a named set rather than a
 * boolean: "it is uploading right now" and "its cloud record is gone" want different sentences, and both
 * are different from a failure.
 */
enum class RejectionReason {
    /** The index no longer holds the row. It may already have been deleted somewhere else. */
    MissingFromIndex,

    /** Its backup record is gone, or was never in a state that asserts a stored home. */
    NotBackedUp,

    /** A send is in flight, so what Telegram holds is not yet known. */
    UploadInFlight,

    /** The queue row names no message, so there is nothing to prove the content is stored. */
    NoAssociation,

    /** No hash was ever taken, so "this file, that message" has never been established. */
    NoContentIdentity,

    /** The cloud index no longer holds the message it was matched against. */
    CloudRecordGone,

    /** In Trash: it is already marked for removal, and freeing its space is a separate decision. */
    InTrash,
    ;

    val isPermanent: Boolean
        get() = this == NotBackedUp || this == NoAssociation || this == NoContentIdentity ||
            this == CloudRecordGone || this == MissingFromIndex
}

/**
 * One item's verdict at the moment deletion was asked for.
 *
 * [candidate] is null when the index no longer holds the row: the id is all that is left of it, and there is
 * no uri, size or name to offer the deletion system. That case has to be representable rather than
 * filtered away, because "you selected 40 items and 3 of them were already gone" is the difference between
 * a report and a silence.
 */
data class EligibilityCheck(
    val mediaStoreId: Long,
    val candidate: FreeUpSpaceCandidate?,
    val rejected: RejectionReason?,
) {
    val isDeletable: Boolean
        get() = rejected == null && candidate != null
}

/**
 * The outcome of a Free Up Space run.
 *
 * [declined] is the common case and the one the design protects: Android asks the user about the deletion
 * of files this app does not own, and a dismiss is not a failure — it is the user having decided, which the
 * screen must be able to say without inventing an error.
 */
sealed interface FreeUpSpaceResult {
    /** Nothing was eligible when the check was repeated. */
    data object NothingEligible : FreeUpSpaceResult

    /** A consent request was built and is on screen; the result is not known until the system answers. */
    data class AwaitingConsent(val requested: Int, val skipped: List<EligibilityCheck>) : FreeUpSpaceResult

    /** The device cannot be asked on this Android version, so nothing was removed. */
    data object ConsentUnavailable : FreeUpSpaceResult

    /** The user did not confirm. Everything they had is still there. */
    data object Declined : FreeUpSpaceResult

    /**
     * Confirmed, and the files were removed.
     *
     * [reclaimedBytes] is the sum of the sizes of the items actually deleted — MediaStore's figures for
     * files that existed a moment ago — rather than a measurement of free space before and after, which no
     * app can attribute to itself while the system is writing elsewhere.
     */
    data class Completed(val deletedCount: Int, val reclaimedBytes: Long) : FreeUpSpaceResult

    /** The system refused the whole batch. */
    data object Failed : FreeUpSpaceResult
}

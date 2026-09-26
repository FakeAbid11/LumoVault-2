package com.lumovault.app.domain.restore

import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType

/**
 * One cloud record, in the form a restore needs.
 *
 * Built from the cloud index rather than from a screen's row model, so the use case can be asked to
 * restore the same message from the Cloud grid, from an item sheet, or from a retry after a restart — and
 * so nothing in [com.lumovault.app.ui.screens] has to know which TDLib field means what.
 *
 * [expectedSizeBytes] is Telegram's own figure for the file it holds, which is the number a download is
 * checked against. [manifestHash] is the hash this device recorded when it uploaded that message, if the
 * message carries one: it is compared and reported, never assumed, because Telegram stores photos and
 * videos in containers of its own choosing.
 */
data class CloudRestoreTarget(
    val chatId: Long,
    val messageId: Long,
    val remoteFileId: String,
    val mediaType: MediaType,
    val mimeType: String,
    val displayName: String,
    val expectedSizeBytes: Long,
    val manifestHash: String = "",
) {
    /** Nothing can be fetched without an id to fetch, and a zero pair is not a message. */
    val isRestorable: Boolean
        get() = remoteFileId.isNotBlank() && chatId != 0L && messageId != 0L

    companion object {
        /**
         * The target for one cloud record, in the words the cloud index uses.
         *
         * Here rather than at each call site because the mapping has one decision in it worth keeping in
         * one place: [CloudMedia.contentHash] is the *manifest's* hash, and it is passed along as the thing
         * to compare against, never as the identity to record. [CloudMedia.fileName] is frequently empty
         * for a photo, which the writer handles by falling back to the name on disk — an empty display name
         * would be filed as `.` and lost.
         */
        fun from(record: CloudMedia): CloudRestoreTarget = CloudRestoreTarget(
            chatId = record.chatId,
            messageId = record.messageId,
            remoteFileId = record.remoteFileId,
            mediaType = record.type,
            mimeType = record.mimeType,
            displayName = record.fileName,
            expectedSizeBytes = record.sizeBytes,
            manifestHash = record.contentHash,
        )
    }
}

/**
 * How a restore attempt ended.
 *
 * Four shapes because they need four different screens: a file that arrived, a file that was never
 * missing, a transfer already running, and a reason. Collapsing the second into the first would let a
 * no-op claim a download happened; collapsing the third into the second would make a double tap look like
 * a completed action.
 */
sealed interface RestoreOutcome {
    /**
     * The bytes are on the device and indexed.
     *
     * [byteIdenticalToUpload] is the honest answer to "is this the file I sent": true when the manifest's
     * hash matches what landed, false when Telegram's copy is its own re-encode, and null when the message
     * carries no manifest at all — which is every message uploaded before Phase 6.
     *
     * [recordedAsBackedUp] is narrower than it looks: the queue row this restore should have settled was
     * owned by a worker at that moment, so the file is on the device while its backup state is still
     * somebody else's to write. [indexed] is the same idea one step earlier — MediaStore has the file, the
     * scanner has not seen it yet. Both are reported rather than smoothed over because the difference
     * between them and a clean success is whether this photo may be offered for upload a second time, which
     * is the one outcome a restore must not leave ambiguous.
     */
    data class Restored(
        val mediaStoreId: Long,
        val contentHash: String,
        val byteIdenticalToUpload: Boolean?,
        val recordedAsBackedUp: Boolean,
        val indexed: Boolean,
    ) : RestoreOutcome

    /** Recognition already found this content on the device, so no bytes were moved. */
    data class AlreadyOnDevice(val mediaStoreId: Long) : RestoreOutcome

    /** A request for this message is already being served by this process. */
    data object InProgress : RestoreOutcome

    /** Nothing arrived. [failure] is the reason, in LumoVault's words. */
    data class Refused(val failure: RestoreFailure) : RestoreOutcome
}

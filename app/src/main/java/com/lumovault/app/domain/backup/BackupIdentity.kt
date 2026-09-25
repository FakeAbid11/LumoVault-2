package com.lumovault.app.domain.backup

import com.lumovault.app.domain.model.MediaType

/**
 * What an item's content was, and the two cheap figures that were true of it at that moment.
 *
 * This pair is the point of PRD section 12's layered approach. Reading a file's bytes costs time and, for
 * the four-gigabyte video at the bottom of a camera roll, more time than the rest of a pass together — so
 * the expensive question ("what are these bytes") is only asked again when one of two cheap facts has
 * moved. [observedSizeBytes] and [observedModifiedSeconds] are those facts: MediaStore reports both
 * without opening the file, and a file that was edited, replaced or re-downloaded has a different one of
 * them. They are the index's own figures rather than a count taken from the stream, deliberately — the
 * next fast check compares against the index, and storing anything else would silently never match for a
 * provider that declines to state a length.
 *
 * That makes them a fast *screen*, never a conclusion. Two files can share a name, a size and a timestamp
 * and differ in content, and matching on this triple instead of [contentHash] would mark a replaced photo
 * as safely stored. Only the hash ever answers "is this backed up".
 */
data class MediaIdentity(
    /** SHA-256 of the original bytes, lowercase hex; empty when the file could not be read. */
    val contentHash: String,
    val observedSizeBytes: Long,
    val observedModifiedSeconds: Long,
) {
    companion object {
        /**
         * A file that could not be hashed.
         *
         * The figures are still recorded, and that is what makes this a *bounded* failure: the item leaves
         * the candidate set until the file itself changes, so one deleted row cannot spend the hashing
         * budget on every pass, while an empty hash keeps it from ever being called backed up.
         */
        fun unreadable(observedSizeBytes: Long, observedModifiedSeconds: Long): MediaIdentity =
            MediaIdentity("", observedSizeBytes, observedModifiedSeconds)
    }
}

/**
 * One local item whose identity is missing or doubtful.
 *
 * Produced by the two candidate queries, which are the layered check made executable: an item with no
 * record, an item recorded but never hashed, or an item whose size or modification time has moved since
 * its hash was taken. Everything else is skipped without a file read, and on a settled library that is
 * the whole library.
 *
 * [knownHash] is the hash on file, empty or stale as it may be. The use case compares it against the one
 * it computes to tell "this file was edited" apart from "we had never looked at this file", which is the
 * difference between revoking a backup association and simply recording a new fact.
 */
data class BackupIdentityCandidate(
    val mediaStoreId: Long,
    val contentUri: String,
    val mediaType: MediaType,
    val mimeType: String,
    val displayName: String,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val knownHash: String,
    /** Null when nothing has ever been recorded about this item. */
    val state: UploadState?,
)

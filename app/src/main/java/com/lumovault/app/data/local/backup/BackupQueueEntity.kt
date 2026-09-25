package com.lumovault.app.data.local.backup

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One backup record — PRD section 61's second table, keyed to the media index.
 *
 * `MediaEntity` says backup state does not live in it, and this is why: a media row is a fact about
 * the device that a scan overwrites, while a backup row is a fact about what was sent to Telegram and
 * must survive every scan. Merging them would mean a re-scan that reuses an id could quietly reset
 * "already backed up".
 *
 * Type, name and dimensions come from `media` through the join in [BackupQueueDao.newestIn] rather than
 * being copied here, so an item that changed on disk cannot be described with a stale copy of itself.
 * The four `content_*` columns are the exception, and they are exactly the parts a change has to be
 * measured *against*: [contentHash] is what this file's bytes were, and [contentSizeBytes] with
 * [contentModifiedSeconds] is the pair that says whether the file has moved since. A hash is a fact
 * about bytes at a moment, and a moment is only recoverable from the file's own metadata at the time.
 *
 * A row exists for an item once recognition has identified it, whether or not the user ever queued it —
 * [UploadState.NotBackedUp] is what such a row says. Without that record the next scan would hash a
 * four-gigabyte video again, which is the cost PRD section 12 exists to avoid.
 */
@Entity(
    tableName = "backup_queue",
    indices = [
        // The worker's only read is "oldest queued row", so state leads the index and the queue time
        // resolves it. Nothing else here is queried by range.
        Index("state", "queued_at"),
        // Recognition's hot path is "does any local record claim this content hash", once per hashed
        // item and once per remote manifest. Unindexed, that is a full scan of a table that grows with
        // the library, per item — which is how a lookup becomes the slowest thing in the app.
        Index("content_hash"),
    ],
)
data class BackupQueueEntity(
    /** Also the MediaStore id: one row per item, which is what makes enqueueing idempotent. */
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long,

    /** [com.lumovault.app.domain.backup.UploadState.storageKey], never an ordinal. */
    @ColumnInfo(name = "state", defaultValue = "queued")
    val state: String,

    /** The channel this row was claimed for, so an account switch mid-queue is visible. */
    @ColumnInfo(name = "chat_id", defaultValue = "0")
    val chatId: Long = 0,

    /** The message Telegram created; 0 until the state is `backed_up`. */
    @ColumnInfo(name = "message_id", defaultValue = "0")
    val messageId: Long = 0,

    /** Send attempts so far, which is what bounds a retryable failure. */
    @ColumnInfo(name = "attempts", defaultValue = "0")
    val attempts: Int = 0,

    /**
     * [com.lumovault.app.domain.backup.BackupFailureKind.name] — the enum's own name rather than a
     * separate key, because these are never shown to a user and a rename is caught by the parser
     * falling back to `unknown`, not by a migration.
     */
    @ColumnInfo(name = "failure", defaultValue = "")
    val failure: String = "",

    /** Where the staged copy lives, kept so a completion or a failure can delete it. */
    @ColumnInfo(name = "staged_path", defaultValue = "")
    val stagedPath: String = "",

    @ColumnInfo(name = "queued_at", defaultValue = "0")
    val queuedAt: Long = 0,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0,

    @ColumnInfo(name = "uploaded_at", defaultValue = "0")
    val uploadedAt: Long = 0,

    /**
     * SHA-256 of the original bytes this record describes, lowercase hex; empty until recognized.
     *
     * This is the value that makes a reinstall survivable: MediaStore ids are reassigned, the row here
     * may not exist at all, and a file name is shared by thousands of unrelated photos — but this digest
     * re-identifies the content wherever it turns up. The matching remote caption is what it is looked up
     * against.
     */
    @ColumnInfo(name = "content_hash", defaultValue = "")
    val contentHash: String = "",

    /**
     * The size `media` reported when [contentHash] was taken, not a count from the hashed stream.
     *
     * Deliberate: the fast check compares this column against what the index says *now*, and a provider
     * that declines to state a length would otherwise never agree with its own file, re-hashing it on
     * every pass forever. What the byte count of the hashed content was is recorded where it matters — in
     * the manifest the message carries.
     */
    @ColumnInfo(name = "content_size_bytes", defaultValue = "0")
    val contentSizeBytes: Long = 0,

    /** MediaStore's modification time at hash time. With the size, the fast check that skips hashing. */
    @ColumnInfo(name = "content_modified_seconds", defaultValue = "0")
    val contentModifiedSeconds: Long = 0,

    /**
     * When a hash was last computed, or 0 when none has been.
     *
     * Non-zero with an empty [contentHash] is a meaningful state, not a broken row: it means the file was
     * read and could not be read. Recording the attempt is what keeps an unreadable file from taking the
     * hashing budget on every pass, and leaving the hash empty is what keeps it from being called backed
     * up.
     */
    @ColumnInfo(name = "hashed_at", defaultValue = "0")
    val hashedAt: Long = 0,
)

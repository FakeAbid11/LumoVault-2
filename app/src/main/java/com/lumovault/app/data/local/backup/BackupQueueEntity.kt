package com.lumovault.app.data.local.backup

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One owed or completed backup — PRD section 61's second table, keyed to the media index.
 *
 * `MediaEntity` says backup state does not live in it, and this is why: a media row is a fact about
 * the device that a scan overwrites, while a backup row is a fact about what was sent to Telegram and
 * must survive every scan. Merging them would mean a re-scan that reuses an id could quietly reset
 * "already backed up".
 *
 * Only the queue's own data is stored. Type, size, name and dimensions come from `media` through the
 * join in [BackupQueueDao.requestFor], so an item that changed on disk cannot be described here with
 * a stale copy of itself.
 *
 * Phase 6's columns are deliberately not here. A hash, a verified timestamp and a remote-manifest
 * reference would each be a column nothing writes, and the point of Phase 6 is that those values
 * become true — not that the schema looks ready.
 */
@Entity(
    tableName = "backup_queue",
    indices = [
        // The worker's only read is "oldest queued row", so state leads the index and the queue time
        // resolves it. Nothing else here is queried by range.
        Index("state", "queued_at"),
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
)

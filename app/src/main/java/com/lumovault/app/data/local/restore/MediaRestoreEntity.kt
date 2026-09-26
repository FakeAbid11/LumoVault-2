package com.lumovault.app.data.local.restore

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import com.lumovault.app.domain.restore.RestoreState

/**
 * One requested download of a cloud original, kept in Room so the request outlives the screen that made it.
 *
 * The key is the *cloud* record — `chat_id` + `message_id` — rather than a local id, because that is the
 * only identity a restore has before it finishes: there is no local file yet, and the MediaStore row is
 * created part-way through. It is also the identity the result has to be reconciled against afterwards.
 *
 * Nothing describing the *content* is stored here. The name, MIME type, size and remote id all belong to
 * `cloud_media`, which is keyed by the same pair and is the index that is refreshed by a sync; repeating
 * them would create a second answer to every question about a file that is, by definition, out of date the
 * moment the index moves. What is kept is the transfer's own state, which exists nowhere else.
 *
 * A row exists while work is happening for two reasons a `StateFlow` in a ViewModel cannot serve. The first
 * is a process death mid-transfer: without this, the app comes back with a file in TDLib's cache that
 * nobody remembers asking for — with it, startup has a list of ids to release and a state to report
 * honestly. The second is that the Cloud grid and the item sheet are two places showing the same download,
 * and two sources of truth for one progress bar disagree visibly.
 *
 * [state] holds a [RestoreState.storageKey] string, not an ordinal, like every other state column here.
 */
@Entity(
    tableName = "media_restore",
    primaryKeys = ["chat_id", "message_id"],
    indices = [Index("state")],
)
data class MediaRestoreEntity(
    /** The storage channel the message lives in. */
    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    /** PRD section 61's `telegramMessageId`, and the half of this row's identity that survives a rescan. */
    @ColumnInfo(name = "message_id")
    val messageId: Long,

    /** TDLib's integer file id for the transfer; 0 until one exists. */
    @ColumnInfo(name = "tdlib_file_id", defaultValue = "0")
    val tdlibFileId: Int = 0,

    /** Bytes TDLib says are readable, from `local.downloadedPrefixSize`. */
    @ColumnInfo(name = "downloaded_bytes", defaultValue = "0")
    val downloadedBytes: Long = 0,

    /** The size Telegram reported for its own copy, which is what a bar is measured against. */
    @ColumnInfo(name = "expected_size_bytes", defaultValue = "0")
    val expectedSizeBytes: Long = 0,

    @ColumnInfo(name = "state", defaultValue = "pending")
    val state: String = RestoreState.Pending.storageKey,

    /** A [com.lumovault.app.domain.restore.RestoreFailureKind] name, or empty. Never free text. */
    @ColumnInfo(name = "failure", defaultValue = "")
    val failure: String = "",

    /** The local index row this restore produced; 0 until the file is in MediaStore and scanned. */
    @ColumnInfo(name = "media_store_id", defaultValue = "0")
    val mediaStoreId: Long = 0,

    /**
     * SHA-256 of the bytes that landed, which is what stops Phase 6 queueing a re-upload.
     *
     * Deliberately *not* the hash in the message's manifest: those two numbers describe different files
     * whenever Telegram stored its own re-encode, and recording the manifest's hash as this row's identity
     * would claim a byte-for-byte match the app has not seen.
     */
    @ColumnInfo(name = "content_hash", defaultValue = "")
    val contentHash: String = "",

    @ColumnInfo(name = "requested_at", defaultValue = "0")
    val requestedAtSeconds: Long = 0,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAtSeconds: Long = 0,
)

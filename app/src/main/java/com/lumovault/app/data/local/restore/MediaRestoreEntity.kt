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
 * A row exists while work is happening for two reasons that a `StateFlow` in a ViewModel cannot serve. The
 * first is a process death mid-transfer: without this, the app comes back with a partial file in a temp
 * directory and nobody who remembers it was asked for — with it, startup has a list of paths to remove and
 * a state to report honestly. The second is that the Cloud grid and the item sheet are two different
 * places showing the same download, and two sources of truth for one progress bar disagree visibly.
 *
 * [state] is a [RestoreState.storageKey] string, not an ordinal, like every other state column here.
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

    /** `MediaType.storageKey`, which decides the MediaStore collection and the TDLib file type. */
    @ColumnInfo(name = "media_type", defaultValue = "")
    val mediaType: String = "",

    @ColumnInfo(name = "mime_type", defaultValue = "")
    val mimeType: String = "",

    /** The name the cloud index knows. Used for the insert only; the local row's name comes from MediaStore. */
    @ColumnInfo(name = "display_name", defaultValue = "")
    val displayName: String = "",

    /**
     * Where the file will be saved, as a MediaStore `RELATIVE_PATH`.
     *
     * Stored rather than derived at save time because the choice is made once, when the request is taken:
     * a retry after a restart must not land the same content in a second folder because the rule for
     * choosing one changed.
     */
    @ColumnInfo(name = "relative_path", defaultValue = "")
    val relativePath: String = "",

    /** TDLib's `remote.id` for the original, which is what `getRemoteFile` is asked for. */
    @ColumnInfo(name = "remote_file_id", defaultValue = "")
    val remoteFileId: String = "",

    /** The size the cloud index holds, used for the pre-flight space check and the progress denominator. */
    @ColumnInfo(name = "expected_size_bytes", defaultValue = "0")
    val expectedSizeBytes: Long = 0,

    /** Bytes TDLib reported downloaded, from `local.downloadedPrefixSize + local.downloadedSize`. */
    @ColumnInfo(name = "downloaded_bytes", defaultValue = "0")
    val downloadedBytes: Long = 0,

    @ColumnInfo(name = "state", defaultValue = "pending")
    val state: String = RestoreState.Pending.storageKey,

    /** A [com.lumovault.app.domain.restore.RestoreFailureKind] name, or empty. Never free text. */
    @ColumnInfo(name = "failure", defaultValue = "")
    val failure: String = "",

    /** The local index row this restore produced; 0 until the file is in MediaStore and scanned. */
    @ColumnInfo(name = "media_store_id", defaultValue = "0")
    val mediaStoreId: Long = 0,

    /** SHA-256 of the bytes that actually landed, which is what stops Phase 6 queueing a re-upload. */
    @ColumnInfo(name = "content_hash", defaultValue = "")
    val contentHash: String = "",

    /**
     * The temporary file this download owns, or empty.
     *
     * Only a path inside the app's own storage ever appears here — TDLib's files directory for the transfer
     * itself, and nothing else — so a startup sweep can remove what a killed process left behind without
     * being handed a filename from a database row and deleting wherever it points.
     */
    @ColumnInfo(name = "temp_path", defaultValue = "")
    val tempPath: String = "",

    @ColumnInfo(name = "requested_at", defaultValue = "0")
    val requestedAtSeconds: Long = 0,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAtSeconds: Long = 0,
)

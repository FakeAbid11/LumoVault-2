package com.lumovault.app.data.local.cloud

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One remote item in the Cloud index — PRD section 9's manifest, restricted to what a Telegram
 * message actually reports.
 *
 * Two PRD-named columns are deliberately absent. `hash` cannot be filled without reading the
 * original, which section 13 forbids during a scan, so it arrives with the backup engine. Latitude
 * and longitude have no source either: TDLib reports GPS only for a `messageLocation`, not for a
 * photo, so the Map phase adds them alongside the caption manifest that will carry them. Adding
 * empty columns now would only promise data nothing writes.
 *
 * Identity is [messageId]. File names are not unique across a channel, and position in a history
 * page changes as soon as one message is deleted, so neither can key a row.
 */
@Entity(
    tableName = "cloud_media",
    indices = [
        // The timeline sorts by date, the header counts by type, and the prune filters by scan id.
        // These are the three queries this table serves; nothing else is indexed.
        Index("date_seconds"),
        Index("media_type"),
        Index("last_seen_scan_id"),
    ],
)
data class CloudMediaEntity(
    @PrimaryKey(autoGenerate = false)
    @ColumnInfo(name = "message_id")
    val messageId: Long,

    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    /** [com.lumovault.app.domain.model.MediaType] storage key, never an ordinal. */
    @ColumnInfo(name = "media_type")
    val mediaType: String,

    @ColumnInfo(name = "mime_type", defaultValue = "")
    val mimeType: String = "",

    @ColumnInfo(name = "file_name", defaultValue = "")
    val fileName: String = "",

    @ColumnInfo(name = "size_bytes", defaultValue = "0")
    val sizeBytes: Long = 0,

    @ColumnInfo(name = "date_seconds")
    val dateSeconds: Long,

    @ColumnInfo(name = "date_source", defaultValue = "telegram_message")
    val dateSource: String = "telegram_message",

    @ColumnInfo(name = "width", defaultValue = "0")
    val width: Int = 0,

    @ColumnInfo(name = "height", defaultValue = "0")
    val height: Int = 0,

    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Long? = null,

    /**
     * TDLib's `remote.id` for the original. A string, so it survives a reinstall of the app and a
     * TDLib database that forgets numeric file handles; it is a reference, and nothing here has
     * downloaded the bytes.
     */
    @ColumnInfo(name = "remote_file_id", defaultValue = "")
    val remoteFileId: String = "",

    /** `remote.id` of the *thumbnail*, which is what the grid renders without touching the original. */
    @ColumnInfo(name = "preview_remote_file_id", defaultValue = "")
    val previewRemoteFileId: String = "",

    @ColumnInfo(name = "caption", defaultValue = "")
    val caption: String = "",

    /** Same scan-generation trick as the local table: prune is one statement, not a huge `NOT IN`. */
    @ColumnInfo(name = "last_seen_scan_id", defaultValue = "0")
    val lastSeenScanId: Long = 0,
)

/**
 * The adopted storage channel, as one row.
 *
 * [ownerUserId] is stored next to [chatId] on purpose: PRD section 73 requires that a chat id from
 * account A never be used under account B, and the pair is what makes that check possible offline.
 */
@Entity(tableName = "cloud_channel")
data class CloudChannelEntity(
    @PrimaryKey
    val id: Int = SINGLETON_ROW_ID,

    @ColumnInfo(name = "chat_id")
    val chatId: Long,

    @ColumnInfo(name = "owner_user_id")
    val ownerUserId: Long,

    @ColumnInfo(name = "protocol_version", defaultValue = "1")
    val protocolVersion: Int = 1,

    /** Resume cursor for the paged history walk. */
    @ColumnInfo(name = "last_scanned_message_id", defaultValue = "0")
    val lastScannedMessageId: Long = 0,

    @ColumnInfo(name = "last_sync_seconds", defaultValue = "0")
    val lastSyncSeconds: Long = 0,
) {
    companion object {
        const val SINGLETON_ROW_ID = 1
    }
}

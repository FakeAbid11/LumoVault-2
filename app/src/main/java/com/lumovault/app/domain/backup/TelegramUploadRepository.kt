package com.lumovault.app.domain.backup

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.BackupManifest
import kotlinx.coroutines.flow.Flow

/**
 * The bytes to send, already where TDLib can read them.
 *
 * [stagedPath] is not a convenience: TDLib's only local input at the pinned revision is
 * `inputFileLocal`, which takes a path, and a `content://` URI is not one. Something has to copy the
 * file first, and that something is [MediaSourceStager] rather than this interface's caller.
 */
data class UploadRequest(
    val mediaStoreId: Long,
    val mediaType: MediaType,
    val mimeType: String,
    val stagedPath: String,
    /** Bytes of the staged copy, which is the figure Telegram will actually receive. */
    val sizeBytes: Long,
    val displayName: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long?,
    /**
     * What the message must declare about these bytes, written into its caption so the backup can identify
     * itself after a reinstall.
     *
     * Not optional in Phase 6. An upload without a manifest is a photo the user has stored and LumoVault
     * cannot recognise — it will be uploaded a second time some day, to a channel that already holds it —
     * which is the exact failure this phase exists to prevent, so there is no path here that sends without
     * one.
     */
    val manifest: BackupManifest,
)

/**
 * What happened to an upload, as it happened.
 *
 * [Progress] may arrive any number of times or none at all — TDLib reports byte counts for a file it
 * is uploading, and if it never associates one with this item there is nothing honest to say, so the
 * flow simply stays quiet rather than inventing a percentage. [Sent] and [Refused] are terminal.
 */
sealed interface UploadEvent {
    data class Progress(val fraction: Float) : UploadEvent
    data class Sent(val chatId: Long, val messageId: Long) : UploadEvent
    data class Refused(val failure: BackupFailure) : UploadEvent
}

/**
 * Sending an original into the LumoVault Backup channel.
 *
 * Deliberately narrow: one staged file in, one message id out. It cannot list chats, cannot choose a
 * destination, and has no download path, so an upload can only land in the channel the caller was told
 * is theirs — [chatId] comes from the persisted association rather than being something this interface
 * can invent or look up.
 */
interface TelegramUploadRepository {
    /** False when this build has no TDLib binary or no credentials — the queue must then not run. */
    val isUsable: Boolean

    /**
     * Sends [request] to [chatId] as a message of the type its media actually is.
     *
     * A cold flow collected exactly once: the worker needs the intermediate progress and the final
     * outcome from one subscription, and a second collector would send the file twice.
     */
    fun upload(chatId: Long, request: UploadRequest): Flow<UploadEvent>
}

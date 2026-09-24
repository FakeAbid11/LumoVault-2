package com.lumovault.app.domain.model

/**
 * One media item that exists in the Telegram channel, described by its remote metadata.
 *
 * Nothing here is the file: the originals stay in Telegram, and PRD section 25 forbids pulling them
 * down just to browse. [previewRemoteFileId] is a *thumbnail* reference for exactly that reason, and
 * it is a string rather than a local path because it may never be downloaded on this device.
 */
data class CloudMedia(
    /**
     * Telegram's message id, the stable remote identity. Not the file name: two backups of
     * `IMG_0001.jpg` from different phones are different photos, and the filename alone would merge
     * them.
     */
    val messageId: Long,
    val chatId: Long,
    val type: MediaType,
    /** Empty for plain photos, where TDLib reports a MIME type only for documents and videos. */
    val mimeType: String,
    /** Empty when Telegram has none, which is the normal case for a photo. */
    val fileName: String,
    /** Bytes of the *original*, from the file metadata; never implies those bytes are local. */
    val sizeBytes: Long,
    val dateSeconds: Long,
    val dateSource: CloudDateSource,
    val width: Int,
    val height: Int,
    /** Seconds, because that is TDLib's unit; null for still images. */
    val durationSeconds: Long?,
    val remoteFileId: String,
    val previewRemoteFileId: String,
    /**
     * Caption text. Phase 4 stores it because it is part of the message, and it is also where the
     * future manifest travels; [com.lumovault.app.domain.telegram.LumoVaultStorageProtocol.isMarker]
     * is run against it during validation.
     */
    val caption: String,
) {
    val hasPreview: Boolean
        get() = previewRemoteFileId.isNotBlank()

    val hasDimensions: Boolean
        get() = width > 0 && height > 0
}

/**
 * Where [CloudMedia.dateSeconds] came from, which decides how the timeline may talk about it.
 *
 * PRD section 42 warns against presenting a message timestamp as a capture date. Until the backup
 * engine writes capture dates into the caption, every cloud row is [TelegramMessage], and the UI
 * groups by it without claiming it is when the photo was taken.
 */
enum class CloudDateSource(val storageKey: String) {
    TelegramMessage("telegram_message"),
    BackupManifest("backup_manifest");

    companion object {
        fun fromStorageKey(key: String?): CloudDateSource =
            entries.firstOrNull { it.storageKey == key } ?: TelegramMessage
    }
}

/** Photo, video and GIF counts for the cloud header line. */
data class CloudTypeCount(
    val mediaType: String,
    val itemCount: Int,
)

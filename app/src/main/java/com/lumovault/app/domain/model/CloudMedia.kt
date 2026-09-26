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
     * Caption text, stored because it is part of the message — and because for a LumoVault backup it is
     * also the manifest: [com.lumovault.app.domain.telegram.BackupManifestFormat] writes the content hash
     * into the media message's own caption, and reads it back here during a scan. Kept raw so the Cloud
     * screen can show what a user actually typed on a photo they added themselves.
     */
    val caption: String,
    /**
     * SHA-256 this message declares, lowercase hex; empty when it declares none.
     *
     * Empty is a real and common answer: every backup uploaded before Phase 6, and every photo the user
     * put in the channel by hand. It is never filled by inference, because a hash this message does not
     * carry cannot later explain why a local file was considered stored.
     */
    val contentHash: String,
) {
    val hasPreview: Boolean
        get() = previewRemoteFileId.isNotBlank()

    val hasDimensions: Boolean
        get() = width > 0 && height > 0

    /** Whether this message identifies its own content, which is what makes it matchable. */
    val isLumoVaultBackup: Boolean
        get() = contentHash.isNotBlank()
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

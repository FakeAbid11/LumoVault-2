package com.lumovault.lumovault.features.backup.domain.model

enum class UploadStatus { queued, uploading, paused, completed, failed }

/**
 * One upload in the backup queue.
 *
 * Ported from the Flutter `UploadTask`. Field semantics worth preserving:
 * - [nextAttemptAt] is a *deferred* backoff point, not a sleep: the engine
 *   sets it and the queue simply skips tasks whose time hasn't come, so one
 *   failing upload never stalls the healthy tasks queued behind it.
 * - [mediaCreatedAt] is the photo's capture date (distinct from [createdAt],
 *   the queue-lifecycle timestamp) and is what gets written into the backup
 *   metadata so a restore reproduces the original timeline.
 */
data class UploadTask(
    val id: String,
    val mediaItemId: String,
    val localFilePath: String,
    val fileName: String,
    val fileSize: Long,
    val fileHash: String,
    val telegramFileId: String? = null,
    val telegramMessageId: String? = null,
    val status: UploadStatus = UploadStatus.queued,
    val progress: Float = 0f,
    val error: String? = null,
    val retryCount: Int = 0,
    /** Epoch millis; null = eligible now. */
    val nextAttemptAt: Long? = null,
    val createdAt: Long,
    val mediaCreatedAt: Long? = null,
    val mediaModifiedAt: Long? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    val failedAt: Long? = null,
    val pausedAt: Long? = null,
    val lastActivityAt: Long? = null,
    val priority: Int = 0,
    /** Poster frame attached as inputThumbnail for video uploads. Transient. */
    val thumbnailPath: String? = null,
    val durationMs: Long? = null,
) {
    val isTerminal: Boolean
        get() = status == UploadStatus.completed || status == UploadStatus.failed

    /** Maximum upload attempts before a task is permanently failed. */
    val canRetry: Boolean
        get() = status == UploadStatus.failed && retryCount < MAX_ATTEMPTS

    companion object {
        const val MAX_ATTEMPTS = 3
    }
}

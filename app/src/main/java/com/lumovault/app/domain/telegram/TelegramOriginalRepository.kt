package com.lumovault.app.domain.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RestoreFailure

/**
 * Fetches one Telegram **original** to a local file, on request.
 *
 * This interface exists because its sibling does not do the job: [TelegramPreviewRepository] is restricted
 * to `FileTypeThumbnail` and to the preview id, and PRD section 25 makes that restriction the point of the
 * Cloud screen — browsing must not pull originals. So the original path is a separate type, called only
 * from a user action, rather than a flag on a method that everything already calls.
 */
interface TelegramOriginalRepository {
    /** False when this build has no TDLib or no credentials, which is a reason a restore cannot start. */
    val isUsable: Boolean

    /**
     * Downloads [original] to a file TDLib owns, reporting byte progress as it goes.
     *
     * [onProgress] is called with TDLib's own counters and only when they advance, so a caller can drive a
     * real bar from it and show nothing at all when the size is unknown. The coroutine is the cancellation
     * mechanism: cancelling this call tells TDLib to stop and releases whatever it had, rather than leaving
     * a transfer running behind a screen that has gone.
     */
    suspend fun download(
        original: RemoteOriginal,
        onProgress: (DownloadProgress) -> Unit = {},
    ): OriginalDownload

    /**
     * Releases the cache file behind a finished [OriginalDownload.Ready].
     *
     * Called after the bytes have been copied into MediaStore, because until then that file is the only
     * copy of what the user asked for. Never throws: a file TDLib keeps a little longer is a disk
     * question, not a user-visible failure.
     */
    suspend fun release(ready: OriginalDownload.Ready)
}

/**
 * Which file of a cloud record to fetch.
 *
 * [remoteFileId] is TDLib's `remote.id` as the cloud index stored it — the original's id, never the
 * preview's. [mediaType] only chooses the `FileType` to ask for, because Telegram stores a video, a photo
 * and an animation under different types and asking for the wrong one is a refusal, not a guess.
 */
data class RemoteOriginal(val remoteFileId: String, val mediaType: MediaType)

/**
 * How far a download has got, in bytes, as TDLib counts them.
 *
 * [totalBytes] is 0 when TDLib has not said how large the file is, which is the honest reason a bar may
 * have to be indeterminate rather than an excuse to invent a denominator.
 */
data class DownloadProgress(val downloadedBytes: Long, val totalBytes: Long) {
    val fraction: Float?
        get() = takeIf { totalBytes > 0L && downloadedBytes >= 0L }
            ?.let { (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) }
}

/** What one download ended as. */
sealed interface OriginalDownload {
    /**
     * The bytes are on disk at [path], inside TDLib's own directory.
     *
     * [fileId] travels with it so the caller can hand it back to [TelegramOriginalRepository.release] —
     * deleting that path directly would leave TDLib's records pointing at a file that is gone.
     */
    data class Ready(val fileId: Int, val path: String, val sizeBytes: Long) : OriginalDownload

    /** Nothing usable landed. The reason is in LumoVault's words, never TDLib's. */
    data class Failed(val failure: RestoreFailure) : OriginalDownload
}

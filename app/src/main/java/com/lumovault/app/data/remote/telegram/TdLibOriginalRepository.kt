package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RestoreFailure
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.telegram.DownloadProgress
import com.lumovault.app.domain.telegram.OriginalDownload
import com.lumovault.app.domain.telegram.RemoteOriginal
import com.lumovault.app.domain.telegram.TelegramOriginalRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * Downloads a Telegram original with TDLib's own three-step shape.
 *
 * A remote id string is not a file handle, so [TdApi.GetRemoteFile] first turns it into a [TdApi.File]
 * with an integer id, [TdApi.DownloadFile] starts the transfer, and only then does a path exist. Both of
 * the first two are answered from TDLib's own state — the scheme documents `getRemoteFile` and `getFile`
 * as *offline methods* — which is why completion is polled here rather than waited for as an event.
 *
 * The alternative is collecting `updateFile` from [TelegramClient.updates], and that flow is
 * `DROP_OLDEST` with no replay because a scrolling grid must not be able to back-pressure TDLib. For a
 * thumbnail that is fine: a missed update costs a retry in two hundred milliseconds. For a four-hundred
 * megabyte video, the update that matters is the last one, and a dropped final update is a restore that
 * hangs. So the loop asks TDLib directly on a timer — cheap, since the answer is local — and updates are
 * left to the code that can tolerate losing them.
 *
 * Every failure comes back as a value rather than an exception, like the preview repository, because the
 * caller's job is to record which of several distinct reasons a download stopped and show the right one.
 * Cancellation is the exception: it is rethrown after TDLib has been told to stop, so a closed screen
 * does not leave bytes arriving for it.
 */
class TdLibOriginalRepository(
    private val client: TelegramClient,
    /** How often completion is asked about. Injectable so a test does not spend real seconds. */
    private val pollMillis: Long = POLL_MILLIS,
    /**
     * How long without a single new readable byte counts as a dead connection.
     *
     * A stall limit rather than a total timeout, because the honest fact about a large video on a weak
     * connection is that it takes a long time, and a fixed deadline would cancel exactly the transfers a
     * user is most waiting for. Three minutes of *nothing* is not a slow transfer, it is no transfer.
     */
    private val stallMillis: Long = STALL_MILLIS,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : TelegramOriginalRepository {

    override val isUsable: Boolean
        get() = client.isUsable

    override suspend fun download(
        original: RemoteOriginal,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): OriginalDownload {
        if (original.remoteFileId.isBlank()) {
            return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
        }
        if (!client.isUsable) {
            return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.NotAuthenticated))
        }

        // Set as soon as TDLib names the file, so the catch below can stop a transfer that had started.
        var startedFileId = 0

        return try {
            val resolved = client.request(remoteLookup(original))
            if (resolved.id <= 0) {
                return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
            }
            startedFileId = resolved.id

            client.request(downloadRequest(resolved.id))
            awaitTransfer(resolved.id, declaredSizeOf(resolved), onProgress)
        } catch (cancelled: CancellationException) {
            // NonCancellable because the coroutine is already torn down: an await here would throw before
            // it reached TDLib, and the transfer would keep downloading into a cache nobody will clear.
            withContext(NonCancellable) { stopTransfer(startedFileId) }
            throw cancelled
        } catch (error: TelegramRequestException) {
            OriginalDownload.Failed(error.toRestoreFailure())
        } catch (error: Exception) {
            OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.Unknown))
        }
    }

    override suspend fun release(ready: OriginalDownload.Ready) {
        if (ready.fileId <= 0) return
        val deletion = TdApi.DeleteFile()
        deletion.fileId = ready.fileId
        // Swallowed on purpose: TDLib keeps its own reference counts, and a file it is still holding is a
        // disk question for its cache, not a reason to tell the user their restored photo failed.
        runCatching { client.request(deletion, RELEASE_TIMEOUT_MILLIS) }
    }

    /**
     * Asks TDLib what it has, until it says the file is complete.
     *
     * Readable bytes are the progress figure — `downloaded_prefix_size` — rather than `downloaded_size`,
     * because the scheme says the latter is only usable for progress and that "the actual file size may be
     * bigger, and some parts of it may contain garbage". A bar that only ever moves over bytes that can
     * genuinely be read cannot claim more than it has.
     */
    private suspend fun awaitTransfer(
        fileId: Int,
        declaredSize: Long,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): OriginalDownload {
        var readable = 0L
        var lastAdvanceAt = nowMillis()
        var missingState = 0

        while (true) {
            val lookup = TdApi.GetFile()
            lookup.fileId = fileId
            val file = client.request(lookup, POLL_TIMEOUT_MILLIS)
            val local = file.local

            if (local == null) {
                // TDLib has the id but no local state at all. One pass is tolerated — the record can be a
                // frame stale — but a row of them means this file will not arrive.
                if (++missingState >= MAX_MISSING_STATE) {
                    return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
                }
                delay(pollMillis)
                continue
            }
            missingState = 0

            if (local.isDownloadingCompleted) {
                val path = local.path.orEmpty()
                if (path.isBlank()) {
                    return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
                }
                if (!File(path).isFile) {
                    // Completed and gone: TDLib's cache was cleaned underneath the transfer. Retrying the
                    // same remote id is the only honest next step, so it is reported as its own cause.
                    return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.Incomplete))
                }
                val size = if (file.size > 0L) file.size else readable
                return OriginalDownload.Ready(fileId = fileId, path = path, sizeBytes = size)
            }

            val total = if (file.size > 0L) file.size else declaredSize
            val now = local.downloadedPrefixSize.coerceAtLeast(0L)
            if (now > readable) {
                readable = now
                lastAdvanceAt = nowMillis()
                onProgress(DownloadProgress(downloadedBytes = now, totalBytes = total))
            }

            if (!local.canBeDownloaded && !local.isDownloadingActive) {
                return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
            }
            if (nowMillis() - lastAdvanceAt >= stallMillis) {
                // Stop before giving up: a transfer TDLib is still running for a caller that has walked
                // away fills the cache for nobody, and nothing else in the app knows to clear it.
                stopTransfer(fileId)
                return OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.Network))
            }

            delay(pollMillis)
        }
    }

    private suspend fun stopTransfer(fileId: Int) {
        if (fileId <= 0) return
        val cancel = TdApi.CancelDownloadFile()
        cancel.fileId = fileId
        // False: stop it even if the request already reached the server. `only_if_pending` true would
        // cancel a download that has not begun and leave one that has running to completion.
        cancel.onlyIfPending = false
        runCatching { client.request(cancel, RELEASE_TIMEOUT_MILLIS) }
    }

    private companion object {
        fun remoteLookup(original: RemoteOriginal): TdApi.GetRemoteFile {
            val lookup = TdApi.GetRemoteFile()
            lookup.remoteFileId = original.remoteFileId
            lookup.fileType = fileTypeFor(original.mediaType)
            return lookup
        }

        fun downloadRequest(fileId: Int): TdApi.DownloadFile {
            val download = TdApi.DownloadFile()
            download.fileId = fileId
            // The ceiling of TDLib's 1-32 range: this is the one file the user is currently waiting on,
            // and previews that arrive later are the correct thing to push behind it.
            download.priority = PRIORITY_RESTORE
            // Whole file, from the start: a restore is only useful as a complete file, and TDLib cancels a
            // limited download once the limit is reached, which would leave a partial file marked done.
            download.offset = 0
            download.limit = 0
            download.synchronous = false
            return download
        }

        /**
         * TDLib's `FileType` for the original.
         *
         * The type is part of the request, not a label: asking for a photo's bytes with the video type is
         * how a download refuses rather than returns the wrong file. [MediaType.Gif] is an animation here
         * because that is how this app sends them (see `TdLibUploadRepository`), and Telegram keeps them
         * under that type.
         */
        fun fileTypeFor(mediaType: MediaType): TdApi.FileType = when (mediaType) {
            MediaType.Photo -> TdApi.FileTypePhoto()
            MediaType.Video -> TdApi.FileTypeVideo()
            MediaType.Gif -> TdApi.FileTypeAnimation()
        }

        fun declaredSizeOf(file: TdApi.File): Long =
            if (file.size > 0L) file.size else file.expectedSize.coerceAtLeast(0L)

        const val PRIORITY_RESTORE = 32
        const val POLL_MILLIS = 500L
        const val STALL_MILLIS = 3 * 60 * 1000L
        const val MAX_MISSING_STATE = 3

        /** [TdApi.GetFile] is answered from TDLib's own state, so a slow answer is already a problem. */
        const val POLL_TIMEOUT_MILLIS = 15_000L

        /** Release and cancel are housekeeping; neither is worth holding a cancelled caller. */
        const val RELEASE_TIMEOUT_MILLIS = 10_000L
    }
}

/**
 * TDLib's error into the restore vocabulary.
 *
 * Top-level and `internal` for the reason `toBackupFailure` gives: which failures are worth another attempt
 * is the consequential decision in this file, and it has to be testable without a network or an account.
 * The distinction that matters most here is between *this message no longer has the file* and *the
 * connection dropped* — the first is a fact the user should be told, the second is a reason to press the
 * same button again.
 */
internal fun TelegramRequestException.toRestoreFailure(): RestoreFailure {
    val token = reason.uppercase()
    val kind = when {
        reason == TIMED_OUT -> RestoreFailureKind.Network

        token.startsWith("FLOOD_WAIT") || token.contains("SLOWMODE") -> RestoreFailureKind.RateLimited

        token.contains("UNAUTHENTICATED") || token.contains("AUTH") || code == 401 ->
            RestoreFailureKind.NotAuthenticated

        // The remote id is the only handle a restore has. These tokens mean the message behind it is gone,
        // deleted, or no longer readable by this account, and no retry will bring it back.
        token.contains("REMOTE_FILE_INVALID") || token.contains("FILE_ID_INVALID") ||
            token.contains("FILE_REFERENCE_EXPIRED") || token.contains("MESSAGE_DELETE") ||
            token.contains("ACCESS") || token.contains("CHAT_NOT_FOUND") ||
            token.contains("CHAT_ID_INVALID") -> RestoreFailureKind.SourceGone

        // ENOSPC surfaces as TDLib's I/O error text; the caller also refuses before starting.
        token.contains("SPACE") || token.contains("ENOSPC") -> RestoreFailureKind.InsufficientSpace

        token.contains("NETWORK") || token.contains("TIMED OUT") || token.contains("RESOLVE") ||
            token.contains("SOCKET") || code in 500..504 -> RestoreFailureKind.Network

        else -> RestoreFailureKind.Unknown
    }
    return RestoreFailure(kind)
}

/** Mirrors [TdLibClient]'s private timeout marker, which is a token rather than a TDLib answer. */
private const val TIMED_OUT = "TDLIB_TIMED_OUT"

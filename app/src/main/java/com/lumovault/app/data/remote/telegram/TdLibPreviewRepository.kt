package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.TelegramPreviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.drinkless.tdlib.TdApi

/**
 * Resolves a Telegram *thumbnail* into a local path the image loader can read.
 *
 * The two-step shape is what TDLib requires: a remote id string is not a file handle, so
 * [TdApi.GetRemoteFile] first converts it into a [TdApi.File] with an integer id, and only that id is
 * ever passed to [TdApi.DownloadFile]. Callers hand over `previewRemoteFileId`, never `remoteFileId`,
 * and the file type asked for is [TdApi.FileTypeThumbnail] — the original is not reachable through
 * this class.
 *
 * Every failure returns null rather than throwing: an unavailable preview is a placeholder on screen,
 * not an error state, and a grid must not fail because one file could not be fetched.
 */
class TdLibPreviewRepository(
    private val client: TelegramClient,
) : TelegramPreviewRepository {
    override val isUsable: Boolean
        get() = client.isUsable

    override suspend fun localPathFor(remoteFileId: String): String? {
        if (remoteFileId.isBlank() || !client.isUsable) return null

        return try {
            val resolved = client.request(
                TdApi.GetRemoteFile().apply {
                    this.remoteFileId = remoteFileId
                    fileType = TdApi.FileTypeThumbnail()
                },
            ).id.takeIf { it > 0 } ?: return null

            client.request(
                TdApi.DownloadFile().apply {
                    fileId = resolved
                    // Low priority: a grid scrolls, and a cell that left the viewport should not be
                    // competing with the item the user stopped on.
                    priority = PRIORITY
                    offset = 0
                    // 0 means "to the end of the file" for this request.
                    limit = 0
                    synchronous = false
                },
            )

            awaitLocalPath(resolved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            null
        }
    }

    /**
     * [TdApi.DownloadFile] starts a transfer and answers immediately, so completion is polled rather
     * than assumed. The attempts are bounded on purpose: a cell that never resolves falls back to the
     * placeholder instead of holding a coroutine open while the user scrolls on.
     */
    private suspend fun awaitLocalPath(fileId: Int): String? {
        repeat(MAX_ATTEMPTS) {
            val local = client.request(TdApi.GetFile().apply { this.fileId = fileId }).local
            if (local?.isDownloadingCompleted == true) {
                return local.path?.takeIf { it.isNotBlank() }
            }
            delay(POLL_MILLIS)
        }
        return null
    }

    private companion object {
        const val PRIORITY = 1
        const val POLL_MILLIS = 250L

        /** ~2 seconds at [POLL_MILLIS], which is generous for a thumbnail and short for a grid cell. */
        const val MAX_ATTEMPTS = 8
    }
}

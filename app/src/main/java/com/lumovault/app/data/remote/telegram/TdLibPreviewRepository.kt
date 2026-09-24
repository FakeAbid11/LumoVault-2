package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.TelegramPreviewRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Resolves a Telegram *thumbnail* into a local path the image loader can read.
 *
 * The two-step shape is what TDLib's JSON interface requires: a remote id string is not a file
 * handle, so `getRemoteFile` first converts it into a `File` with an integer id, and only that id is
 * ever passed to `downloadFile`. Callers hand over `previewRemoteFileId`, never `remoteFileId`, and
 * the file type asked for is `fileTypeThumbnail` — the original is not reachable through this class.
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
                method = "getRemoteFile",
                params = buildJsonObject {
                    put("remote_file_id", remoteFileId)
                    put("file_type", buildJsonObject { put(TYPE, "fileTypeThumbnail") })
                },
            ).fileId() ?: return null

            client.request(
                method = "downloadFile",
                params = buildJsonObject {
                    put("file_id", resolved)
                    // Low priority: a grid scrolls, and a cell that left the viewport should not be
                    // competing with the item the user stopped on.
                    put("priority", PRIORITY)
                    put("offset", 0)
                    // 0 means "to the end of the file" for this request.
                    put("limit", 0)
                    put("synchronous", false)
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
     * `downloadFile` starts a transfer and answers immediately, so completion is polled rather than
     * assumed. The attempts are bounded on purpose: a cell that never resolves falls back to the
     * placeholder instead of holding a coroutine open while the user scrolls on.
     */
    private suspend fun awaitLocalPath(fileId: Int): String? {
        repeat(MAX_ATTEMPTS) {
            val file = client.request("getFile", buildJsonObject { put("file_id", fileId) })
            val local = file.objectOf("local")
            if (local?.flag("is_downloading_completed") == true) {
                return local.stringOf("path").takeIf { it.isNotBlank() }
            }
            delay(POLL_MILLIS)
        }
        return null
    }

    private fun JsonObject.fileId(): Int? = intOf("id").takeIf { it > 0 }

    private companion object {
        const val TYPE = "@type"
        const val PRIORITY = 1
        const val POLL_MILLIS = 250L

        /** ~2 seconds at [POLL_MILLIS], which is generous for a thumbnail and short for a grid cell. */
        const val MAX_ATTEMPTS = 8
    }
}

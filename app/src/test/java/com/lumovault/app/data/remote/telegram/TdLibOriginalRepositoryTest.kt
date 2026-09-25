package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.telegram.DownloadProgress
import com.lumovault.app.domain.telegram.OriginalDownload
import com.lumovault.app.domain.telegram.RemoteOriginal
import java.io.File
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The download path, driven through the same typed TDLib classes production uses.
 *
 * What is asserted here is the part a device cannot check for you: which file was asked for, under which
 * `FileType`, whether the numbers shown as progress are TDLib's own or invented, and whether a transfer
 * the app has stopped caring about was actually stopped. Bytes crossing a network into TDLib's cache
 * cannot happen on a build server, so the transfer is scripted and the fake answers with `TdApi.File`
 * objects built field by field as the generated Java declares them.
 */
internal class TdLibOriginalRepositoryTest {

    @Test
    fun aRemoteIdBecomesAFileIdBeforeAnythingIsDownloaded() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        val landing = tempFile()
        client.answer = script(
            resolved = file(id = 7, size = 1_000L),
            transfer = listOf(
                downloadable(7, size = 1_000L, readable = 1_000L, completed = true, path = landing.absolutePath),
            ),
        )

        val result = repository(client).download(RemoteOriginal("remote-7", MediaType.Photo))

        assertTrue("expected a completed download, got $result", result is OriginalDownload.Ready)
        val ready = result as OriginalDownload.Ready
        assertEquals(landing.absolutePath, ready.path)
        assertEquals(1_000L, ready.sizeBytes)
        assertEquals(7, ready.fileId)

        // The two-step shape, asserted rather than assumed: a remote id string is not a file handle, and
        // handing one to downloadFile is a bug that looks exactly like a dead download.
        val lookup = client.sentOf<TdApi.GetRemoteFile>().single()
        assertEquals("remote-7", lookup.remoteFileId)
        assertTrue(lookup.fileType is TdApi.FileTypePhoto)

        val download = client.sentOf<TdApi.DownloadFile>().single()
        assertEquals(7, download.fileId)
        assertEquals(0L, download.offset)
        assertEquals(0L, download.limit)
        assertFalse("synchronous would hold the answer until the whole file arrived", download.synchronous)
        assertTrue(download.priority > 1)
    }

    @Test
    fun eachKindOfMediaIsAskedForUnderTheTypeItWasStoredAs() = runBlocking<Unit> {
        // A wrong FileType is a refusal from Telegram rather than a wrong file, so all three are pinned —
        // including the GIF, which is an animation to Telegram and a GIF to the user.
        listOf(
            MediaType.Photo to { type: TdApi.FileType? -> type is TdApi.FileTypePhoto },
            MediaType.Video to { type -> type is TdApi.FileTypeVideo },
            MediaType.Gif to { type -> type is TdApi.FileTypeAnimation },
        ).forEach { (mediaType, matches) ->
            val client = FakeTelegramClient()
            client.answer = script(
                resolved = file(id = 3, size = 10L),
                transfer = listOf(
                    // Nothing downloadable and nothing running: the loop exits on the first look, which is
                    // the fastest honest way to get a request list out of a transfer that will not happen.
                    downloadable(
                        3,
                        size = 10L,
                        readable = 0L,
                        completed = false,
                        canBeDownloaded = false,
                        active = false,
                    ),
                ),
            )

            val result = repository(client).download(RemoteOriginal("remote-3", mediaType))

            assertTrue("expected a refusal for $mediaType, got $result", result is OriginalDownload.Failed)
            val asked = client.sentOf<TdApi.GetRemoteFile>().single().fileType
            // The class name, never the object: `TdApi.Object.toString()` is a native method, so
            // interpolating one of these throws UnsatisfiedLinkError on a build server instead of
            // arriving as the assertion failure the test is about.
            assertTrue(
                "$mediaType asked for the wrong TDLib file type: ${asked?.javaClass?.simpleName}",
                matches(asked),
            )
        }
    }

    @Test
    fun progressCarriesOnlyBytesTdLibSaysAreReadableAndIsSilentWhileTheyDoNotMove() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        val landing = tempFile()
        client.answer = script(
            resolved = file(id = 8, size = 1_000L),
            transfer = listOf(
                downloadable(8, size = 1_000L, readable = 0L, completed = false),
                downloadable(8, size = 1_000L, readable = 250L, completed = false),
                downloadable(8, size = 1_000L, readable = 250L, completed = false),
                downloadable(8, size = 1_000L, readable = 750L, completed = false),
                downloadable(8, size = 1_000L, readable = 750L, completed = false),
                downloadable(8, size = 1_000L, readable = 1_000L, completed = true, path = landing.absolutePath),
            ),
        )

        val seen = mutableListOf<DownloadProgress>()
        val result = repository(client).download(RemoteOriginal("remote-8", MediaType.Photo)) { seen += it }

        assertTrue(result is OriginalDownload.Ready)
        assertEquals(
            "one callback per advance, so a bar moves on bytes rather than on polls",
            listOf(250L, 750L),
            seen.map { it.downloadedBytes },
        )
        assertEquals(listOf(0.25f, 0.75f), seen.mapNotNull { it.fraction })
    }

    @Test
    fun aFileWhoseSizeTelegramNeverSaidHasNoFractionRatherThanAnInventedOne() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        val landing = tempFile()
        client.answer = script(
            resolved = file(id = 9, size = 0L),
            transfer = listOf(
                downloadable(9, size = 0L, readable = 400L, completed = false),
                downloadable(9, size = 0L, readable = 400L, completed = true, path = landing.absolutePath),
            ),
        )

        val seen = mutableListOf<DownloadProgress>()
        repository(client).download(RemoteOriginal("remote-9", MediaType.Photo)) { seen += it }

        assertEquals(1, seen.size)
        assertNull("an unknown total must render as indeterminate, not as a guess", seen.single().fraction)
        assertEquals(400L, seen.single().downloadedBytes)
    }

    @Test
    fun aTransferThatStopsMovingIsStoppedRatherThanLeftRunning() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        client.answer = script(
            resolved = file(id = 11, size = 5_000L),
            transfer = List(400) { downloadable(11, size = 5_000L, readable = 0L, completed = false) },
        )

        val result = repository(client, pollMillis = 1L, stallMillis = 20L)
            .download(RemoteOriginal("remote-11", MediaType.Video))

        val failure = result as OriginalDownload.Failed
        assertEquals(RestoreFailureKind.Network, failure.failure.kind)
        val cancel = client.sentOf<TdApi.CancelDownloadFile>().single()
        assertEquals(11, cancel.fileId)
        assertFalse(
            "only_if_pending true would cancel a download that had not started and leave this one running",
            cancel.onlyIfPending,
        )
    }

    @Test
    fun cancellingTheCallTellsTdlibToStopTheTransfer() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        client.answer = script(
            resolved = file(id = 12, size = 5_000L),
            transfer = List(500) { downloadable(12, size = 5_000L, readable = 0L, completed = false) },
        )
        val repository = repository(client, pollMillis = 1L, stallMillis = 60_000L)

        var outcome: OriginalDownload? = null
        val job = launch {
            outcome = repository.download(RemoteOriginal("remote-12", MediaType.Photo))
        }
        delay(30L)
        job.cancelAndJoin()

        assertNull("a cancelled download must not report a result", outcome)
        val cancel = client.sentOf<TdApi.CancelDownloadFile>().single()
        assertEquals(12, cancel.fileId)
    }

    @Test
    fun aRefusalFromTelegramBecomesTheReasonTheUserIsTold() = runBlocking<Unit> {
        // The mapping is the whole difference between a screen that says "this photo is no longer in your
        // channel" and one that says "try again" about a thing that will never work.
        listOf(
            TelegramRequestException(401, "UNAUTHENTICATED") to RestoreFailureKind.NotAuthenticated,
            TelegramRequestException(420, "FLOOD_WAIT_312") to RestoreFailureKind.RateLimited,
            TelegramRequestException(400, "REMOTE_FILE_INVALID") to RestoreFailureKind.SourceGone,
            TelegramRequestException(400, "FILE_REFERENCE_EXPIRED") to RestoreFailureKind.SourceGone,
            TelegramRequestException(400, "CHAT_ID_INVALID") to RestoreFailureKind.SourceGone,
            TelegramRequestException(507, "no space left on device") to RestoreFailureKind.InsufficientSpace,
            TelegramRequestException(500, "NETWORK_IPPORT_NONE") to RestoreFailureKind.Network,
            TelegramRequestException(0, "TDLIB_TIMED_OUT") to RestoreFailureKind.Network,
            TelegramRequestException(400, "SOMETHING_UNFORESEN") to RestoreFailureKind.Unknown,
        ).forEach { (error, expected) ->
            val client = FakeTelegramClient()
            client.answer = { function ->
                if (function is TdApi.GetRemoteFile) throw error
                TdApi.Ok()
            }

            val result = repository(client).download(RemoteOriginal("remote-x", MediaType.Photo))

            val failure = result as OriginalDownload.Failed
            assertEquals("${error.code}/${error.reason} mapped the wrong kind", expected, failure.failure.kind)
            assertEquals(expected.retryable, failure.failure.retryable)
        }
    }

    @Test
    fun nothingIsAskedOfTdlibWithoutAFileIdOrAWorkingClient() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        val blank = repository(client).download(RemoteOriginal("  ", MediaType.Photo))
        assertTrue(blank is OriginalDownload.Failed)
        assertTrue("a blank remote id costs TDLib nothing", client.sent.isEmpty())

        val unusable = FakeTelegramClient(usable = false)
        val failure = repository(unusable).download(RemoteOriginal("remote-1", MediaType.Photo))
            as OriginalDownload.Failed
        assertEquals(RestoreFailureKind.NotAuthenticated, failure.failure.kind)
        assertTrue("an unconfigured build must not have asked anything of TDLib", unusable.sent.isEmpty())
    }

    @Test
    fun aFileThatLandedAndThenVanishedIsReportedAsIncomplete() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        client.answer = script(
            resolved = file(id = 13, size = 900L),
            transfer = listOf(
                downloadable(13, size = 900L, readable = 900L, completed = true, path = "/does/not/exist.jpg"),
            ),
        )

        val failure = repository(client).download(RemoteOriginal("remote-13", MediaType.Photo))
            as OriginalDownload.Failed
        assertEquals(RestoreFailureKind.Incomplete, failure.failure.kind)
    }

    @Test
    fun releaseHandsTdLibItsOwnFileIdBackAndNeverThrows() = runBlocking<Unit> {
        val client = FakeTelegramClient()

        repository(client).release(OriginalDownload.Ready(fileId = 21, path = "/tmp/x", sizeBytes = 1L))
        assertEquals(21, client.sentOf<TdApi.DeleteFile>().single().fileId)

        // TDLib may refuse, because the file is still referenced elsewhere. A restore that reported
        // failure over a cache entry outliving its usefulness would be worse than the entry.
        val refusing = FakeTelegramClient()
        refusing.answer = { throw TelegramRequestException(400, "FILE_HAS_REFS") }
        repository(refusing).release(OriginalDownload.Ready(fileId = 22, path = "/tmp/x", sizeBytes = 1L))
        assertEquals(1, refusing.sentOf<TdApi.DeleteFile>().size)
    }

    @Test
    fun aFileWithNoLocalStateAtAllIsNotWaitedOnForever() = runBlocking<Unit> {
        val client = FakeTelegramClient()
        val orphan = file(id = 14, size = 500L)
        client.answer = script(resolved = orphan, transfer = List(10) { orphan })

        val failure = repository(client, pollMillis = 1L).download(RemoteOriginal("remote-14", MediaType.Photo))
            as OriginalDownload.Failed
        assertEquals(RestoreFailureKind.SourceGone, failure.failure.kind)
        assertEquals(
            "three looks before giving up, not one, because a record can be a frame stale",
            3,
            client.sentOf<TdApi.GetFile>().size,
        )
    }

    private fun repository(
        client: FakeTelegramClient,
        pollMillis: Long = 1L,
        stallMillis: Long = 60_000L,
    ) = TdLibOriginalRepository(client, pollMillis = pollMillis, stallMillis = stallMillis)

    /**
     * Answers the two id-producing requests from [resolved] and the polling from [transfer], repeating the
     * last entry forever after so a test never fails by running out of answers — which would arrive as an
     * unrelated cast error rather than as the assertion the test is about.
     */
    private fun script(
        resolved: TdApi.File,
        transfer: List<TdApi.File>,
    ): (TdApi.Function<*>) -> TdApi.Object {
        var look = 0
        return { function ->
            when (function) {
                is TdApi.GetRemoteFile -> resolved
                is TdApi.DownloadFile -> resolved
                is TdApi.GetFile -> transfer[look.coerceAtMost(transfer.lastIndex)].also { look += 1 }
                else -> TdApi.Ok()
            }
        }
    }

    private fun tempFile(): File = File.createTempFile("lumovault-restore", ".jpg").apply { deleteOnExit() }

    private companion object {
        fun file(id: Int, size: Long): TdApi.File {
            val remote = TdApi.RemoteFile()
            remote.id = "remote-$id"

            val file = TdApi.File()
            file.id = id
            file.size = size
            file.expectedSize = size
            file.remote = remote
            return file
        }

        /**
         * A [TdApi.File] as TDLib answers `getFile` mid-download.
         *
         * Written with an explicit receiver rather than `apply`, for the reason `FakeTelegramClient` gives:
         * these parameter names are the same words as the generated field names, and inside `apply`
         * `path = path` can read the field it is meant to be writing.
         */
        fun downloadable(
            id: Int,
            size: Long,
            readable: Long,
            completed: Boolean,
            path: String = "",
            canBeDownloaded: Boolean = true,
            active: Boolean = true,
        ): TdApi.File {
            val local = TdApi.LocalFile()
            local.path = path
            local.canBeDownloaded = canBeDownloaded
            local.canBeDeleted = true
            local.isDownloadingActive = active
            local.isDownloadingCompleted = completed
            local.downloadOffset = 0L
            local.downloadedPrefixSize = readable
            local.downloadedSize = readable

            val file = TdApi.File()
            file.id = id
            file.size = size
            file.expectedSize = size
            file.local = local
            return file
        }
    }
}

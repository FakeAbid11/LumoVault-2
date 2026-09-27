package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The thumbnail resolver, driven against [FakeTelegramClient].
 *
 * What is worth checking here is the two ways a cloud gallery ends up showing placeholders over pictures
 * that exist. Both were real: the request asserted a file type the app cannot know — the ids it stores are
 * a photo's smallest rendered size for one media type and a separate thumbnail file for another, which are
 * not the same TDLib type — and a request made before TDLib had its parameters was refused, with the cell
 * asking exactly once and keeping the refusal forever.
 *
 * A path is only ever returned for a file TDLib says has finished arriving, because handing Coil a path to
 * half a JPEG renders a torn cell rather than nothing.
 */
class TdLibPreviewRepositoryTest {
    private val client = FakeTelegramClient()

    private fun repository(session: Boolean = true) =
        TdLibPreviewRepository(client = client, ensureSession = { session })

    private fun completedFile(id: Int, path: String?) = TdApi.File().apply {
        this.id = id
        local = TdApi.LocalFile().apply {
            this.path = path
            isDownloadingCompleted = path != null
        }
    }

    @Test
    fun aThumbnailIsResolvedWithoutAssertingWhatKindOfTelegramFileItIs() = runBlocking {
        val thumbnail = completedFile(77, "/data/tdlib/files/thumb-77")
        client.answer = { function ->
            when (function) {
                is TdApi.GetRemoteFile, is TdApi.GetFile, is TdApi.DownloadFile -> thumbnail
                else -> TdApi.Ok()
            }
        }

        assertEquals("/data/tdlib/files/thumb-77", repository().localPathFor(REMOTE_ID))

        val lookup = client.sentOf<TdApi.GetRemoteFile>().single()
        assertEquals(REMOTE_ID, lookup.remoteFileId)
        assertNull(
            "the scheme documents file_type as \"pass null if unknown\", and this app does not know: a " +
                "photo's size and a video's thumbnail are different TDLib types, so any type named here " +
                "is wrong for half the library",
            lookup.fileType,
        )
        assertEquals(
            "only the integer id the lookup returned reaches a download — a remote id string is not a handle",
            77,
            client.sentOf<TdApi.DownloadFile>().single().fileId,
        )
    }

    @Test
    fun nothingIsAskedOfTDLibUntilThereIsASession() = runBlocking {
        assertNull(repository(session = false).localPathFor(REMOTE_ID))

        assertEquals(
            "a request before the parameters are set is refused, and that refusal used to be remembered " +
                "by the cell as \"no thumbnail\"",
            0,
            client.sent.size,
        )
    }

    @Test
    fun anItemWithNoThumbnailReferenceAsksNothing() = runBlocking {
        assertNull(repository().localPathFor("   "))

        assertEquals("nothing was ever stored for this item, so there is nothing to fetch", 0, client.sent.size)
    }

    @Test
    fun aRemoteIdTDLibDoesNotRecogniseIsNoPreviewRatherThanACrash() = runBlocking {
        client.answer = { throw TelegramRequestException(400, "Remote file id is incorrect") }

        assertNull(repository().localPathFor(REMOTE_ID))
    }

    @Test
    fun aFileThatNeverFinishesYieldsNoPathAndStopsAsking() = runBlocking {
        // Id 0 is TDLib's "no file": the resolver must not go on to hand that to a download.
        client.answer = { function ->
            when (function) {
                is TdApi.GetRemoteFile -> completedFile(0, null)
                else -> TdApi.Ok()
            }
        }

        assertNull(repository().localPathFor(REMOTE_ID))
        assertEquals(0, client.sentOf<TdApi.DownloadFile>().size)
    }

    @Test
    fun aBuildWithoutTdLibIsRefusedBeforeAnyRequest() = runBlocking {
        val unusable = FakeTelegramClient(usable = false)
        val repository = TdLibPreviewRepository(client = unusable, ensureSession = { true })

        assertNull(repository.localPathFor(REMOTE_ID))
        assertEquals(0, unusable.sent.size)
    }

    private companion object {
        /** Opaque to this layer: TDLib is the only thing that reads a remote id's contents. */
        const val REMOTE_ID = "BQADBAADGJYxAb67mK9C0"
    }
}

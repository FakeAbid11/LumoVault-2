package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.UploadEvent
import com.lumovault.app.domain.backup.UploadRequest
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.BackupManifest
import com.lumovault.app.domain.telegram.BackupManifestFormat
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the upload layer puts on the wire, checked against the generated TDLib classes.
 *
 * A real send cannot be tested off a device, and claiming otherwise would be exactly the fabricated
 * green this project refuses. What can be proven here is everything up to that boundary: which message
 * type each kind of media becomes, that the file handed over is the staged original rather than a
 * re-encoded stand-in, that the destination is the chat the caller was given, and that every TDLib
 * error this build can meet lands on a failure the queue knows how to treat.
 */
class TdLibUploadRepositoryTest {
    private val client = FakeTelegramClient()

    private val repository = TdLibUploadRepository(client)

    private fun request(type: MediaType, durationMillis: Long? = null) = UploadRequest(
        mediaStoreId = 1L,
        mediaType = type,
        mimeType = when (type) {
            MediaType.Photo -> "image/jpeg"
            MediaType.Video -> "video/mp4"
            MediaType.Gif -> "image/gif"
        },
        stagedPath = STAGED,
        sizeBytes = 4096L,
        displayName = "IMG_1.jpg",
        width = 4,
        height = 3,
        durationMillis = durationMillis,
        manifest = BackupManifest(
            contentHash = HASH,
            sizeBytes = 4096L,
            modifiedSeconds = 1_790_000_000L,
            fileName = "IMG_1.jpg",
        ),
    )

    private val manifestText = BackupManifestFormat.encode(
        BackupManifest(HASH, 4096L, 1_790_000_000L, "IMG_1.jpg"),
    )

    private fun sentContent(): TdApi.InputMessageContent =
        (client.sentOf<TdApi.SendMessage>().single().inputMessageContent)!!

    @Test
    fun aPhotoBecomesAPhotoMessageCarryingTheStagedOriginal() = runBlocking {
        client.answer = { TdApi.Message().apply { id = 7001L } }

        val outcome = repository.upload(CHAT, request(MediaType.Photo)).toList().last()

        assertEquals(UploadEvent.Sent(CHAT, 7001L), outcome)

        val sent = client.sentOf<TdApi.SendMessage>().single()
        assertEquals("only the adopted channel is ever a target", CHAT, sent.chatId)
        assertNull("a backup post is not a reply and not a topic message", sent.replyTo)
        assertNull(sent.topicId)

        val content = sent.inputMessageContent as TdApi.InputMessagePhoto
        val photo = content.photo
        assertEquals("the bytes as staged, unaltered", STAGED, (photo.photo as TdApi.InputFileLocal).path)
        assertEquals(4, photo.width)
        assertEquals(3, photo.height)
        assertEquals("no stickers are being added", 0, photo.addedStickerFileIds.size)
        assertNull("nothing is generated or recompressed here", photo.video)
        assertEquals(
            "the caption is how this message will identify itself after a reinstall",
            manifestText,
            content.caption.text,
        )
        assertEquals(
            "declared with no entities, because the manifest is metadata and not formatted text",
            0,
            content.caption.entities.size,
        )
    }

    @Test
    fun everyMediaTypeCarriesTheSameManifestSoNoneOfThemIsUnrecognisableLater() = runBlocking {
        client.answer = { TdApi.Message().apply { id = 7004L } }

        repository.upload(CHAT, request(MediaType.Video, durationMillis = 8_000L)).toList()
        val videoCaption = (sentContent() as TdApi.InputMessageVideo).caption.text

        client.sent.clear()
        repository.upload(CHAT, request(MediaType.Gif, durationMillis = 3_000L)).toList()
        val gifCaption = (sentContent() as TdApi.InputMessageAnimation).caption.text

        assertEquals(manifestText, videoCaption)
        assertEquals(manifestText, gifCaption)
        // A photo the Cloud screen cannot name is a photo whose manifest is the only name it has; the
        // hash inside that caption is what a reinstall matches the local file against.
        assertTrue(HASH in videoCaption)
    }

    @Test
    fun aVideoKeepsItsOwnTypeAndReportsDurationInWholeSeconds() = runBlocking {
        client.answer = { TdApi.Message().apply { id = 7002L } }

        repository.upload(CHAT, request(MediaType.Video, durationMillis = 12_500L)).toList()

        val video = (sentContent() as TdApi.InputMessageVideo).video
        assertEquals(12, video.duration)
        assertEquals(STAGED, (video.video as TdApi.InputFileLocal).path)
        assertNull("no cover is invented; TDLib would generate one only if asked", video.cover)
        assertNull(video.thumbnail)
    }

    @Test
    fun aGifStaysAGifRatherThanBeingSentAsAPhotoOrADocument() = runBlocking {
        client.answer = { TdApi.Message().apply { id = 7003L } }

        repository.upload(CHAT, request(MediaType.Gif, durationMillis = 3000L)).toList()

        val animation = (sentContent() as TdApi.InputMessageAnimation).animation
        assertEquals(3, animation.duration)
        assertTrue(animation.animation is TdApi.InputFileLocal)
        // The mapper on the way back indexes messageAnimation and reads image/gif from it; sending a
        // GIF any other way would make the user's own backups invisible to the Cloud screen.
        assertEquals(TdApi.InputMessageAnimation::class.java, sentContent().javaClass)
    }

    @Test
    fun aMessageWithoutAnIdIsNotTreatedAsASuccess() = runBlocking {
        // TDLib reserves 0. Storing it as a message id would put a ✓ on an item nothing created.
        client.answer = { TdApi.Message() }

        val outcome = repository.upload(CHAT, request(MediaType.Photo)).toList().last()

        assertEquals(BackupFailureKind.Unknown, (outcome as UploadEvent.Refused).failure.kind)
    }

    @Test
    fun anUnusableBuildRefusesBeforeTouchingTelegram() = runBlocking {
        client.usable = false

        val outcome = repository.upload(CHAT, request(MediaType.Photo)).toList()

        assertEquals(1, outcome.size)
        assertEquals(
            BackupFailureKind.NotAuthenticated,
            (outcome.single() as UploadEvent.Refused).failure.kind,
        )
        assertEquals("no request left the process", 0, client.sent.size)
    }

    @Test
    fun everyErrorThisBuildCanMeetLandsOnAFailureTheQueueKnowsHowToTreat() {
        // Which failures are worth another attempt decides how long a user waits and how many useless
        // requests Telegram sees: a deleted file retried four times is four refusals, and a dropped
        // connection written off as permanent is a backup nobody told the user about.
        val cases = listOf(
            Case(420, "FLOOD_WAIT_312", BackupFailureKind.RateLimited),
            Case(401, "UNAUTHENTICATED", BackupFailureKind.NotAuthenticated),
            Case(400, "CHAT_ID_INVALID", BackupFailureKind.ChannelUnavailable),
            Case(400, "CHANNEL_PRIVATE", BackupFailureKind.ChannelUnavailable),
            Case(400, "LOCAL_FILE_DOES_NOT_EXIST", BackupFailureKind.SourceUnreadable),
            Case(400, "FILE_REFERENCE_EXPIRED", BackupFailureKind.SourceUnreadable),
            Case(500, "INTERNAL_SERVER_ERROR", BackupFailureKind.Network),
            Case(0, "TDLIB_TIMED_OUT", BackupFailureKind.Network),
            Case(400, "MESSAGE_NOT_FOUND", BackupFailureKind.Rejected),
            Case(406, "Something this build has never seen", BackupFailureKind.Unknown),
        )

        cases.forEach { case ->
            val mapped = TelegramRequestException(case.code, case.reason).toBackupFailure()
            assertEquals("${case.reason} (code ${case.code})", case.expected, mapped.kind)
        }
    }

    @Test
    fun aRetryableSetIsExactlyTheFailuresTheQueueShouldKeepTrying() {
        listOf(
            Case(420, "FLOOD_WAIT_312", retryable = true),
            Case(400, "LOCAL_FILE_DOES_NOT_EXIST", retryable = false),
            Case(400, "CHAT_ID_INVALID", retryable = false),
            Case(401, "UNAUTHENTICATED", retryable = true),
        ).forEach { case ->
            assertEquals(
                case.reason,
                case.retryable,
                TelegramRequestException(case.code, case.reason).toBackupFailure().retryable,
            )
        }
    }

    @Test
    fun uploadProgressOnlyReportsBytesItCanAttributeToThisItem() {
        assertEquals(0.5f, file(size = 200L, uploaded = 100L).fractionOf(STAGED))
        assertEquals("never more than done", 1f, file(size = 200L, uploaded = 900L).fractionOf(STAGED))
        assertNull(
            "another item's file is not this item's progress",
            file(size = 200L, uploaded = 100L, path = "/staging/media_2.jpg").fractionOf(STAGED),
        )
        assertNull("no size means no fraction, not zero percent", file(size = 0L, uploaded = 10L).fractionOf(STAGED))
        assertNull("nothing uploaded yet", file(size = 200L, uploaded = 0L).fractionOf(STAGED))
        assertNull("a blank path cannot be matched", file(size = 200L, uploaded = 100L).fractionOf(""))
    }

    private class Case(
        val code: Int,
        val reason: String,
        val expected: BackupFailureKind? = null,
        val retryable: Boolean = false,
    )
}

private const val CHAT = 55_000_000_000L

private const val STAGED = "/data/user/0/com.lumovault.app/cache/backup_staging/media_1.jpg"

/** A digest of the right shape and length; which file it came from is this test's business alone. */
private const val HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

/** A [TdApi.File] as TDLib reports one mid-upload: size known, path ours, bytes partly away. */
private fun file(size: Long, uploaded: Long, path: String = STAGED): TdApi.File {
    val local = TdApi.LocalFile().apply { this.path = path }
    val remote = TdApi.RemoteFile().apply { uploadedSize = uploaded }

    return TdApi.File().apply {
        this.size = size
        this.local = local
        this.remote = remote
    }
}

private fun TdApi.File.fractionOf(stagedPath: String): Float? = uploadFractionOf(this, stagedPath)

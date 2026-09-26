package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.TelegramUploadRepository
import com.lumovault.app.domain.backup.UploadEvent
import com.lumovault.app.domain.backup.UploadRequest
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.BackupManifest
import com.lumovault.app.domain.telegram.BackupManifestFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Sends an original file into the storage channel with TDLib's typed message content, choosing the
 * message type by what the media actually is: a photo as [TdApi.MessagePhoto], a video as
 * [TdApi.MessageVideo], a GIF as [TdApi.MessageAnimation].
 *
 * The type matters on the way back as much as the way there. The existing [TdCloudMapper] indexes
 * exactly those three and ignores every other content, so a backup uploaded as a document would be
 * invisible in the user's own library — and a GIF uploaded as a photo would stop being a GIF.
 *
 * The bytes go over as [TdApi.InputFileLocal] and nothing here resizes, re-encodes or recompresses
 * them. TDLib performs the upload as part of sending the message, which is why there is no separate
 * upload call and why the timeout is measured in hours rather than the two minutes a metadata read is
 * given.
 *
 * What this cannot promise is that Telegram stores those bytes unchanged: Telegram re-encodes the photo
 * and video containers as it chooses, and an animation may be transcoded to a looping video. The original
 * is preserved as far as Telegram's media path permits, which is a property of the server and not of this
 * call.
 *
 * The manifest in the caption says so precisely, and that precision is what keeps Phase 6 honest. The hash
 * it carries is of the bytes *this call sent*, which is the fact recognition needs — "the file on this
 * phone with digest X is already in the channel" — and it is emphatically not a claim that Telegram's
 * stored copy still hashes to X, because nothing here downloads it to check. Recognising a backup is done
 * against what the user's own device hashed, never against a server-side echo of it.
 */
class TdLibUploadRepository(
    private val client: TelegramClient,
) : TelegramUploadRepository {

    override val isUsable: Boolean
        get() = client.isUsable

    /**
     * Emits whatever progress TDLib reports, then exactly one terminal event.
     *
     * Progress is correlated by the staging path because that is the only identifier shared between
     * the file we wrote and the [TdApi.File] TDLib reports mid-upload — the message, and so its file
     * ids, does not exist until the send returns. If TDLib reports no path or a different one, no
     * [UploadEvent.Progress] arrives at all and the UI is left with the state rather than an invented
     * percentage.
     */
    override fun upload(chatId: Long, request: UploadRequest) = channelFlow {
        if (!client.isUsable) {
            send(UploadEvent.Refused(BackupFailure(BackupFailureKind.NotAuthenticated)))
            return@channelFlow
        }

        val stagedPath = request.stagedPath
        val collectProgress = launch {
            client.updates
                .filterIsInstance<TdApi.UpdateFile>()
                .collect { update ->
                    update.file.fractionFor(stagedPath)?.let { send(UploadEvent.Progress(it)) }
                }
        }

        val outcome = try {
            val message = client.request(sendMessage(chatId, request), UPLOAD_TIMEOUT_MILLIS)
            if (message.id == 0L) {
                UploadEvent.Refused(BackupFailure(BackupFailureKind.Unknown))
            } else {
                UploadEvent.Sent(chatId, message.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: TelegramRequestException) {
            UploadEvent.Refused(error.toBackupFailure())
        } catch (error: Exception) {
            UploadEvent.Refused(BackupFailure(BackupFailureKind.Unknown))
        } finally {
            collectProgress.cancel()
        }

        send(outcome)
    }

    private fun sendMessage(chatId: Long, request: UploadRequest): TdApi.SendMessage {
        val source = TdApi.InputFileLocal()
        source.path = request.stagedPath

        val content = when (request.mediaType) {
            MediaType.Photo -> {
                val photo = TdApi.InputPhoto()
                photo.photo = source
                photo.addedStickerFileIds = IntArray(0)
                photo.width = request.width
                photo.height = request.height
                TdApi.InputMessagePhoto().apply {
                    this.photo = photo
                    caption = captionFor(request.manifest)
                }
            }

            MediaType.Video -> {
                val video = TdApi.InputVideo()
                video.video = source
                video.thumbnail = null
                video.cover = null
                video.startTimestamp = 0
                video.addedStickerFileIds = IntArray(0)
                video.duration = request.durationSeconds
                video.width = request.width
                video.height = request.height
                // Asks Telegram to present it as streamable. It changes no byte of what is sent.
                video.supportsStreaming = true
                TdApi.InputMessageVideo().apply {
                    this.video = video
                    caption = captionFor(request.manifest)
                }
            }

            MediaType.Gif -> {
                val animation = TdApi.InputAnimation()
                animation.animation = source
                animation.thumbnail = null
                animation.addedStickerFileIds = IntArray(0)
                animation.duration = request.durationSeconds
                animation.width = request.width
                animation.height = request.height
                TdApi.InputMessageAnimation().apply {
                    this.animation = animation
                    caption = captionFor(request.manifest)
                }
            }
        }

        // Topic, reply, options and markup are each documented as "pass null" when they do not apply:
        // a backup is a plain media post.
        val send = TdApi.SendMessage()
        send.chatId = chatId
        send.topicId = null
        send.replyTo = null
        send.options = null
        send.replyMarkup = null
        send.inputMessageContent = content
        return send
    }

    /**
     * The message's caption: this backup's manifest, so the stored file says what content it is.
     *
     * A [TdApi.FormattedText] with an empty entity array rather than a null one, because `entities` is a
     * vector TDLib iterates. No text entity is declared over the manifest — it is metadata, not formatted
     * text, and an entity whose range did not survive Telegram's own parsing would be a claim about
     * something this call cannot check.
     */
    private fun captionFor(manifest: BackupManifest): TdApi.FormattedText {
        val caption = TdApi.FormattedText()
        caption.text = BackupManifestFormat.encode(manifest)
        caption.entities = emptyArray()
        return caption
    }

    private fun TdApi.File.fractionFor(stagedPath: String): Float? = uploadFractionOf(this, stagedPath)

    private companion object {
        /**
         * An upload is bounded by the file and the connection, not by a metadata-shaped deadline. Six
         * hours exceeds any plausible single send on a phone and still ends a wedged request before
         * the worker that issued it could outlive its own work.
         */
        const val UPLOAD_TIMEOUT_MILLIS = 6L * 60 * 60 * 1000
    }
}

/**
 * TDLib's error into the queue's vocabulary.
 *
 * File-level and `internal` rather than private because which failures are worth another attempt is the
 * single most consequential decision in this file and it must be testable: a deleted file retried four
 * times is four refusals and a user still waiting, and a dropped connection treated as permanent loses
 * a backup nobody told the user about.
 */
internal fun TelegramRequestException.toBackupFailure(): BackupFailure {
    val token = reason.uppercase()
    val kind = when {
        // [TdLibClient]'s own marker, not a TDLib token: nothing came back, which is a network
        // condition rather than a rejection.
        reason == TIMED_OUT -> BackupFailureKind.Network

        token.startsWith("FLOOD_WAIT") || token.contains("SLOWMODE") -> BackupFailureKind.RateLimited

        token.contains("UNAUTHENTICATED") || token.contains("AUTH") || code == 401 ->
            BackupFailureKind.NotAuthenticated

        token.contains("CHAT_NOT_FOUND") || token.contains("CHAT_ID_INVALID") ||
            token.contains("CHANNEL_PRIVATE") || token.contains("USER_DEACTIVATED") ->
            BackupFailureKind.ChannelUnavailable

        token.contains("FILE_REFERENCE_EXPIRED") || token.contains("FILE_NOT_FOUND") ||
            token.contains("LOCAL_FILE_") -> BackupFailureKind.SourceUnreadable

        token.contains("NETWORK") || token.contains("TIMED OUT") || token.contains("RESOLVE") ||
            token.contains("SOCKET") || code in 500..504 -> BackupFailureKind.Network

        // TDLib's 400 means the request is incorrect; sending it again says the same incorrect thing.
        // Unclassified errors get the bounded retry instead.
        code == 400 -> BackupFailureKind.Rejected

        else -> BackupFailureKind.Unknown
    }
    return BackupFailure(kind)
}

/** Mirrors [TdLibClient]'s private timeout marker, which is a token rather than a TDLib answer. */
private const val TIMED_OUT = "TDLIB_TIMED_OUT"

/** TDLib's media durations are whole seconds; MediaStore's are milliseconds. */
private val UploadRequest.durationSeconds: Int
    get() = ((durationMillis ?: 0L) / 1000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

/**
 * How much of this file Telegram has, as a fraction, or null when TDLib has said nothing usable.
 *
 * The staging path is the only handle available before the message exists, so a file whose local path
 * does not match this item is not this item — and reporting someone else's progress as this one's
 * would be worse than reporting none. Null is therefore the answer for a blank path, a file with no
 * known size, or an upload TDLib has not started counting yet.
 *
 * Top-level and `internal` rather than a private method because it is the one part of this class that
 * can be checked without a device: a flow whose timing depends on when a collector subscribes is not a
 * test, it is a race.
 */
internal fun uploadFractionOf(file: TdApi.File, stagedPath: String): Float? {
    if (stagedPath.isBlank() || file.local?.path != stagedPath || file.size <= 0L) return null
    val uploaded = file.remote?.uploadedSize ?: return null
    if (uploaded <= 0L) return null
    return (uploaded.toFloat() / file.size.toFloat()).coerceIn(0f, 1f)
}

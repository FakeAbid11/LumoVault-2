package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.CloudDateSource
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import org.drinkless.tdlib.TdApi

/**
 * TDLib's message and chat objects to LumoVault's cloud model.
 *
 * Pure functions over objects TDLib has already decoded, which is what makes the remote protocol
 * testable without a Telegram account: every rule below — what counts as a GIF, which size becomes a
 * preview, when a channel is ours — is exercised from a hand-built [TdApi.Message] rather than
 * against live TDLib.
 *
 * Nothing in here can download anything: it reads the metadata that arrived in the history page and
 * records remote references. PRD section 25's "no automatic original downloads" is enforced by the
 * absence of any other option.
 */
internal object TdCloudMapper {
    private const val GIF_MIME_TYPE = "image/gif"

    /** A [TdApi.Message], or null when it carries no media LumoVault indexes. */
    fun toCloudMedia(message: TdApi.Message, chatId: Long): CloudMedia? {
        val messageId = message.id.takeIf { it != 0L } ?: return null
        val date = message.date.toLong().takeIf { it > 0 } ?: return null

        val described = when (val content = message.content) {
            is TdApi.MessagePhoto -> photo(content)
            is TdApi.MessageVideo -> video(content)
            is TdApi.MessageAnimation -> animation(content)
            is TdApi.MessageDocument -> document(content)
            else -> null
        } ?: return null

        return CloudMedia(
            messageId = messageId,
            chatId = chatId,
            type = described.type,
            mimeType = described.mimeType,
            fileName = described.fileName,
            sizeBytes = described.sizeBytes,
            dateSeconds = date,
            // Section 42: this is when the message reached Telegram, and the model says so rather
            // than dressing it up as a capture date.
            dateSource = CloudDateSource.TelegramMessage,
            width = described.width,
            height = described.height,
            durationSeconds = described.durationSeconds,
            remoteFileId = described.originalRemoteId,
            previewRemoteFileId = described.previewRemoteId,
            caption = described.caption,
        )
    }

    /** Text of a [TdApi.MessageText], which is where a channel-description-less marker is found. */
    fun textOf(message: TdApi.Message): String? =
        (message.content as? TdApi.MessageText)?.text?.text

    /** The highest protocol version among [messages] that is a marker, or null if there is none. */
    fun markerVersionIn(messages: List<TdApi.Message>): Int? =
        messages.mapNotNull { textOf(it) }
            .mapNotNull { LumoVaultStorageProtocol.markerVersion(it) }
            .maxOrNull()

    fun titleOf(chat: TdApi.Chat): String = chat.title.trim()

    /**
     * A storage channel is a broadcast supergroup.
     *
     * There is no `TdApi.ChatTypeChannel` in TDLib — that name was a plausible guess and would have
     * made every real channel fail validation. Channels are [TdApi.ChatTypeSupergroup] with `isChannel`.
     */
    fun isBroadcastChannel(chat: TdApi.Chat): Boolean {
        val type = chat.type as? TdApi.ChatTypeSupergroup ?: return false
        return type.isChannel
    }

    fun supergroupIdOf(chat: TdApi.Chat): Long? =
        (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId?.takeIf { it != 0L }

    /** Only a channel this account created or administers can be its own storage. */
    fun isOwnedByMe(supergroup: TdApi.Supergroup): Boolean = when (supergroup.status) {
        is TdApi.ChatMemberStatusCreator, is TdApi.ChatMemberStatusAdministrator -> true
        else -> false
    }

    fun descriptionOf(supergroupFullInfo: TdApi.SupergroupFullInfo): String =
        supergroupFullInfo.description.orEmpty()

    /** Marker text also travels in the channel description, which one cheap request can read. */
    fun markerVersionInDescription(description: String): Int? =
        LumoVaultStorageProtocol.markerVersion(description)

    private class Described(
        val type: MediaType,
        val mimeType: String,
        val fileName: String,
        val sizeBytes: Long,
        val width: Int,
        val height: Int,
        val durationSeconds: Long?,
        val originalRemoteId: String,
        val previewRemoteId: String,
        val caption: String,
    )

    private fun photo(content: TdApi.MessagePhoto): Described? {
        val sizes = content.photo?.sizes?.filter { it.photo != null }.orEmpty()
        val measured = sizes.filter { it.width > 0 && it.height > 0 }
            .ifEmpty { sizes }
            .ifEmpty { return null }

        val largest = measured.maxByOrNull { it.area } ?: return null
        val smallest = measured.minByOrNull { it.area } ?: largest

        return Described(
            type = MediaType.Photo,
            mimeType = "",
            fileName = "",
            sizeBytes = fileSizeOf(largest.photo),
            width = largest.width,
            height = largest.height,
            durationSeconds = null,
            originalRemoteId = remoteIdOf(largest.photo),
            // The smallest rendered size, never the largest: `photo.sizes` ends with the full image,
            // and picking by position would turn every grid cell into an original download.
            previewRemoteId = remoteIdOf(smallest.photo),
            caption = content.caption.textOrEmpty(),
        )
    }

    private fun video(content: TdApi.MessageVideo): Described? {
        val video = content.video ?: return null
        return Described(
            type = MediaType.Video,
            mimeType = video.mimeType.orEmpty(),
            fileName = video.fileName.orEmpty(),
            sizeBytes = fileSizeOf(video.video),
            width = video.width,
            height = video.height,
            durationSeconds = video.duration.toLong().takeIf { it > 0 },
            originalRemoteId = remoteIdOf(video.video),
            // A video's thumbnail is a separate file from the video itself, so the grid never needs
            // the original.
            previewRemoteId = remoteIdOf(video.thumbnail?.file),
            caption = content.caption.textOrEmpty(),
        )
    }

    private fun animation(content: TdApi.MessageAnimation): Described? {
        val animation = content.animation ?: return null
        val mimeType = animation.mimeType.orEmpty()
        return Described(
            // TDLib calls every animated media file an animation, including MP4s. Only a real GIF is
            // a GIF; anything else animating in the library is a video.
            type = if (mimeType.equals(GIF_MIME_TYPE, ignoreCase = true)) MediaType.Gif else MediaType.Video,
            mimeType = mimeType,
            fileName = animation.fileName.orEmpty(),
            sizeBytes = fileSizeOf(animation.animation),
            width = animation.width,
            height = animation.height,
            durationSeconds = animation.duration.toLong().takeIf { it > 0 },
            originalRemoteId = remoteIdOf(animation.animation),
            previewRemoteId = remoteIdOf(animation.thumbnail?.file),
            caption = content.caption.textOrEmpty(),
        )
    }

    /**
     * GIFs uploaded as documents keep working. Telegram has moved GIF-bearing files between
     * [TdApi.MessageAnimation] and [TdApi.MessageDocument] across releases, and the MIME type — not
     * the wrapper — is what makes a GIF one, so a document with `image/gif` is indexed and every
     * other document is not media at all.
     */
    private fun document(content: TdApi.MessageDocument): Described? {
        val document = content.document ?: return null
        val mimeType = document.mimeType.orEmpty()
        if (!mimeType.equals(GIF_MIME_TYPE, ignoreCase = true)) return null

        return Described(
            type = MediaType.Gif,
            mimeType = mimeType,
            fileName = document.fileName.orEmpty(),
            sizeBytes = fileSizeOf(document.document),
            // TDLib's `document` carries no dimensions at all — only `animation`, `video` and
            // `photoSize` do. Zero rather than a guess, because the Cloud screen renders "unknown
            // size" for these and an invented number would be indistinguishable from a reported one.
            width = 0,
            height = 0,
            durationSeconds = null,
            originalRemoteId = remoteIdOf(document.document),
            previewRemoteId = remoteIdOf(document.thumbnail?.file),
            caption = content.caption.textOrEmpty(),
        )
    }

    private fun TdApi.FormattedText?.textOrEmpty(): String = this?.text.orEmpty()

    private val TdApi.PhotoSize.area: Long
        get() = width.toLong() * height.toLong()

    /** [TdApi.File.size] is the real size; `expectedSize` is what remains before it is known. */
    private fun fileSizeOf(file: TdApi.File?): Long =
        file?.size?.takeIf { it > 0 } ?: file?.expectedSize?.takeIf { it > 0 } ?: 0L

    private fun remoteIdOf(file: TdApi.File?): String = file?.remote?.id.orEmpty()
}

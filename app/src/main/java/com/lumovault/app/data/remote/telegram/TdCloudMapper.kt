package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.CloudDateSource
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import kotlinx.serialization.json.JsonObject

/**
 * TDLib's message and chat objects to LumoVault's cloud model.
 *
 * Pure functions over already-parsed JSON, which is what makes the remote protocol testable without
 * a Telegram account: every rule below — what counts as a GIF, which size becomes a preview, when a
 * channel is ours — is exercised from a fixture rather than against live TDLib.
 *
 * Nothing in here can download anything: it reads the metadata that arrived in the history page and
 * records remote references. PRD section 25's "no automatic original downloads" is enforced by the
 * absence of any other option.
 */
internal object TdCloudMapper {
    private const val GIF_MIME_TYPE = "image/gif"

    /** A `message` object, or null when it carries no media LumoVault indexes. */
    fun toCloudMedia(message: JsonObject, chatId: Long): CloudMedia? {
        val messageId = message.longOf("id") ?: return null
        val content = message.objectOf("content") ?: return null
        val date = message.intOf("date").toLong().takeIf { it > 0 } ?: return null

        val described = when (content.type()) {
            "messagePhoto" -> photo(content)
            "messageVideo" -> video(content)
            "messageAnimation" -> animation(content)
            "messageDocument" -> document(content)
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

    /** Text of a `messageText`, which is where a channel-description-less marker would be found. */
    fun textOf(message: JsonObject): String? =
        message.objectOf("content")
            ?.takeIf { it.type() == "messageText" }
            ?.objectOf("text")
            ?.stringOrNull("text")

    /** The highest protocol version among [messages] that is a marker, or null if there is none. */
    fun markerVersionIn(messages: List<JsonObject>): Int? =
        messages.mapNotNull { textOf(it) }
            .mapNotNull { LumoVaultStorageProtocol.markerVersion(it) }
            .maxOrNull()

    fun chatIdOf(chat: JsonObject): Long? = chat.longOf("id")

    fun titleOf(chat: JsonObject): String = chat.stringOf("title").trim()

    /**
     * A storage channel is a broadcast supergroup.
     *
     * There is no `chatTypeChannel` in TDLib — that name was a plausible guess and would have made
     * every real channel fail validation. Channels are `chatTypeSupergroup` with `is_channel`.
     */
    fun isBroadcastChannel(chat: JsonObject): Boolean {
        val type = chat.objectOf("type") ?: return false
        return type.type() == "chatTypeSupergroup" && type.flag("is_channel")
    }

    fun supergroupIdOf(chat: JsonObject): Long? = chat.objectOf("type")?.longOf("supergroup_id")

    /** Only a channel this account created or administers can be its own storage. */
    fun isOwnedByMe(supergroup: JsonObject): Boolean =
        supergroup.objectOf("status")?.type() in setOf("chatMemberStatusCreator", "chatMemberStatusAdministrator")

    fun descriptionOf(supergroupFullInfo: JsonObject): String = supergroupFullInfo.stringOf("description")

    fun userIdOf(user: JsonObject): Long? = user.longOf("id")

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

    private fun photo(content: JsonObject): Described? {
        val photo = content.objectOf("photo") ?: return null
        val sizes = photo.arrayOf("sizes").filter { size ->
            size.objectOf("photo") != null && size.intOf("width") > 0 && size.intOf("height") > 0
        }.ifEmpty { photo.arrayOf("sizes").filter { it.objectOf("photo") != null } }

        val largest = sizes.maxByOrNull { it.intOf("width").toLong() * it.intOf("height").toLong() } ?: return null
        val smallest = sizes.minByOrNull { it.intOf("width").toLong() * it.intOf("height").toLong() } ?: largest

        return Described(
            type = MediaType.Photo,
            mimeType = "",
            fileName = "",
            sizeBytes = fileSizeOf(largest),
            width = largest.intOf("width"),
            height = largest.intOf("height"),
            durationSeconds = null,
            originalRemoteId = remoteIdOf(largest.objectOf("photo")),
            // The smallest rendered size, never the largest: `photo.sizes` ends with the full image,
            // and picking by position would turn every grid cell into an original download.
            previewRemoteId = remoteIdOf(smallest.objectOf("photo")),
            caption = content.objectOf("caption").stringOfText(),
        )
    }

    private fun video(content: JsonObject): Described? {
        val video = content.objectOf("video") ?: return null
        return Described(
            type = MediaType.Video,
            mimeType = video.stringOf("mime_type"),
            fileName = video.stringOf("file_name"),
            sizeBytes = fileSizeOf(video.objectOf("video")),
            width = video.intOf("width"),
            height = video.intOf("height"),
            durationSeconds = video.longOf("duration"),
            originalRemoteId = remoteIdOf(video.objectOf("video")),
            // A video's thumbnail is a separate file from the video itself, so the grid never needs
            // the original.
            previewRemoteId = remoteIdOf(video.objectOf("thumbnail")?.objectOf("file")),
            caption = content.objectOf("caption").stringOfText(),
        )
    }

    private fun animation(content: JsonObject): Described? {
        val animation = content.objectOf("animation") ?: return null
        val mimeType = animation.stringOf("mime_type")
        return Described(
            // TDLib calls every animated media file an animation, including MP4s. Only a real GIF is
            // a GIF; anything else animating in the library is a video.
            type = if (mimeType.equals(GIF_MIME_TYPE, ignoreCase = true)) MediaType.Gif else MediaType.Video,
            mimeType = mimeType,
            fileName = animation.stringOf("file_name"),
            sizeBytes = fileSizeOf(animation.objectOf("animation")),
            width = animation.intOf("width"),
            height = animation.intOf("height"),
            durationSeconds = animation.longOf("duration"),
            originalRemoteId = remoteIdOf(animation.objectOf("animation")),
            previewRemoteId = remoteIdOf(animation.objectOf("thumbnail")?.objectOf("file")),
            caption = content.objectOf("caption").stringOfText(),
        )
    }

    /**
     * GIFs uploaded as documents keep working. Telegram has moved GIF-bearing files between
     * `messageAnimation` and `messageDocument` across releases, and the MIME type — not the wrapper —
     * is what makes a GIF one, so a document with `image/gif` is indexed and every other document is
     * not media at all.
     */
    private fun document(content: JsonObject): Described? {
        val document = content.objectOf("document") ?: return null
        if (!document.stringOf("mime_type").equals(GIF_MIME_TYPE, ignoreCase = true)) return null

        return Described(
            type = MediaType.Gif,
            mimeType = document.stringOf("mime_type"),
            fileName = document.stringOf("file_name"),
            sizeBytes = fileSizeOf(document.objectOf("document")),
            width = document.intOf("width"),
            height = document.intOf("height"),
            durationSeconds = null,
            originalRemoteId = remoteIdOf(document.objectOf("document")),
            previewRemoteId = remoteIdOf(document.objectOf("thumbnail")?.objectOf("file")),
            caption = content.objectOf("caption").stringOfText(),
        )
    }

    private fun JsonObject?.stringOfText(): String = this?.stringOf("text").orEmpty()

    /** `file.size` is the real size; `expected_size` is what remains before it is known. */
    private fun fileSizeOf(file: JsonObject?): Long =
        file?.longOf("size").takeIfNonZero() ?: file?.longOf("expected_size").takeIfNonZero() ?: 0L

    private fun Long?.takeIfNonZero(): Long? = this?.takeIf { it > 0 }

    private fun remoteIdOf(file: JsonObject?): String = file?.objectOf("remote")?.stringOf("id").orEmpty()
}

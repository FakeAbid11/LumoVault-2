package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudFailureException
import com.lumovault.app.domain.telegram.CloudHistoryPage
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The cloud side of TDLib, spoken entirely through [TelegramClient]'s JSON interface.
 *
 * Every request here is a *metadata* request. Reading history yields file sizes and remote
 * identifiers, never bytes, and no method below calls `downloadFile` — that is what keeps PRD
 * section 25's rule structural rather than a promise to behave.
 *
 * Method and field names were read out of `td/generate/scheme/td_api.tl` at the commit pinned in
 * `.github/workflows/build-tdlib.yml`; TDLib has renamed several of them since the tutorials that
 * circulate, which is why each one is written as the scheme states rather than as memory suggests.
 */
class TdLibCloudRepository(
    private val client: TelegramClient,
) : TelegramCloudRepository {
    override val isUsable: Boolean
        get() = client.isUsable

    override suspend fun accountUserId(): Long =
        TdCloudMapper.userIdOf(request("getMe"))
            ?: throw CloudFailureException(CloudFailure(CloudFailure.Kind.NotAuthenticated))

    /**
     * `searchChats` searches the chats TDLib already knows about this account — deliberately *not*
     * `searchChatsOnServer`, which queries Telegram's public directory and could return a stranger's
     * channel that merely shares the name.
     */
    override suspend fun findStorageChannel(): Long? {
        val response = request(
            "searchChats",
            buildJsonObject {
                put("query", LumoVaultStorageProtocol.CHANNEL_TITLE)
                put("type_filter", buildJsonObject { put(TYPE, "searchChatTypeFilterChannel") })
                put("limit", SEARCH_LIMIT)
            },
        )

        return chatIdsOf(response).firstOrNull { validateChannel(it) is CloudChannelVerdict.Valid }
    }

    /**
     * Name is only a candidate. A chat is adopted when it is a broadcast channel, this account owns
     * it, and the marker is present in its description or among its earliest messages.
     */
    override suspend fun validateChannel(chatId: Long): CloudChannelVerdict {
        val chat = try {
            client.request("getChat", buildJsonObject { put("chat_id", chatId) })
        } catch (error: TelegramRequestException) {
            return if (error.isMissingChat()) CloudChannelVerdict.NotFound else CloudChannelVerdict.NotAChannel
        } catch (error: Exception) {
            throw CloudFailureException(CloudFailure(CloudFailure.Kind.RequestFailed), error)
        }

        if (!TdCloudMapper.isBroadcastChannel(chat)) return CloudChannelVerdict.NotAChannel
        if (!TdCloudMapper.titleOf(chat).equals(LumoVaultStorageProtocol.CHANNEL_TITLE, ignoreCase = true)) {
            return CloudChannelVerdict.MarkerMissing
        }

        val supergroupId = TdCloudMapper.supergroupIdOf(chat) ?: return CloudChannelVerdict.NotAChannel

        val supergroup = requestOrNull("getSupergroup", supergroupId)
            ?: return CloudChannelVerdict.NotFound
        if (!TdCloudMapper.isOwnedByMe(supergroup)) return CloudChannelVerdict.NotOwned

        val version = markerVersion(chatId, supergroupId)
        return when {
            version == null -> CloudChannelVerdict.MarkerMissing
            // A channel written by a newer protocol may hold records this build cannot parse; reading
            // it would be the kind of partial-truth the cloud index must not become.
            version > LumoVaultStorageProtocol.VERSION -> CloudChannelVerdict.MarkerTooNew(version)
            else -> CloudChannelVerdict.Valid
        }
    }

    /**
     * Creates the storage channel and writes the marker twice: once where a cheap single request can
     * read it back (the description) and once as a message, which survives a user editing their
     * channel bio.
     */
    override suspend fun createStorageChannel(): Long {
        val created = request(
            "createNewSupergroupChat",
            buildJsonObject {
                put("title", LumoVaultStorageProtocol.CHANNEL_TITLE)
                put("is_forum", false)
                // TDLib has no chatTypeChannel; a broadcast supergroup *is* a channel.
                put("is_channel", true)
                put("description", LumoVaultStorageProtocol.markerText())
                put("location", JsonNull)
                put("message_auto_delete_time", 0)
                put("for_import", false)
            },
        )
        val chatId = TdCloudMapper.chatIdOf(created)
            ?: throw CloudFailureException(CloudFailure(CloudFailure.Kind.ChannelCreationFailed))

        request(
            "sendMessage",
            buildJsonObject {
                put("chat_id", chatId)
                put("topic_id", JsonNull)
                put("reply_to", JsonNull)
                put("options", JsonNull)
                put("reply_markup", JsonNull)
                put("input_message_content", markerMessage())
            },
        )
        return chatId
    }

    override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): CloudHistoryPage {
        val response = request(
            "getChatHistory",
            buildJsonObject {
                put("chat_id", chatId)
                put("from_message_id", fromMessageId)
                put("offset", 0)
                // TDLib caps a page at 100 and returns fewer when it decides so; asking for more is
                // not an error, it just is not honoured.
                put("limit", limit.coerceIn(1, MAX_PAGE))
                // false: the whole point is to discover what is remote.
                put("only_local", false)
            },
        )

        val messages = messagesOf(response)
        val oldest = messages.mapNotNull { it.longOf("id") }.minOrNull()

        return CloudHistoryPage(
            media = messages.mapNotNull { TdCloudMapper.toCloudMedia(it, chatId) },
            oldestMessageId = oldest ?: 0,
            // History comes back newest-first, so the walk is over when the ids stop moving down or
            // the first message is reached — never when a page count says so.
            reachedBeginning = messages.isEmpty() || oldest == null || oldest <= FIRST_MESSAGE_ID,
            totalRemoteCount = totalCountOf(response),
        )
    }

    private fun markerMessage() = buildJsonObject {
        put(TYPE, "inputMessageText")
        put(
            "text",
            buildJsonObject {
                put(TYPE, "formattedText")
                put("text", LumoVaultStorageProtocol.markerText())
                put("entities", buildJsonArray { })
            },
        )
        put("link_preview_options", JsonNull)
        put("clear_draft", false)
    }

    /**
     * The marker is normally the channel's first message, and `getChatHistory` walks backwards from
     * a given id, so a page requested from [EARLIEST_PROBE_ID] returns the earliest messages a
     * channel can have. The description is checked first because it costs one request and does not
     * depend on message ids being dense.
     */
    private suspend fun markerVersion(chatId: Long, supergroupId: Long): Int? {
        val fullInfo = requestOrNull("getSupergroupFullInfo", supergroupId)
        if (fullInfo != null) {
            TdCloudMapper.markerVersionInDescription(TdCloudMapper.descriptionOf(fullInfo))?.let { return it }
        }
        return probeHistory(chatId)
    }

    private suspend fun probeHistory(chatId: Long): Int? = try {
        val response = client.request(
            "getChatHistory",
            buildJsonObject {
                put("chat_id", chatId)
                put("from_message_id", EARLIEST_PROBE_ID)
                put("offset", 0)
                put("limit", MAX_PAGE)
                put("only_local", false)
            },
        )
        TdCloudMapper.markerVersionIn(messagesOf(response))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        null
    } catch (error: Exception) {
        throw CloudFailureException(CloudFailure(CloudFailure.Kind.RequestFailed), error)
    }

    /** A failed informational request means an absent field, not a failed validation. */
    private suspend fun requestOrNull(method: String, supergroupId: Long): JsonObject? = try {
        client.request(method, buildJsonObject { put("supergroup_id", supergroupId) })
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private suspend fun request(method: String, params: JsonObject = buildJsonObject { }): JsonObject = try {
        client.request(method, params)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        throw CloudFailureException(CloudFailure(error.toCloudKind(), error.code), error)
    } catch (error: Exception) {
        throw CloudFailureException(CloudFailure(CloudFailure.Kind.Unexpected), error)
    }

    private fun TelegramRequestException.toCloudKind(): CloudFailure.Kind = when {
        reason.contains("CHAT_NOT_FOUND") || reason.contains("ACCESS_DENIED") -> CloudFailure.Kind.ChannelUnusable
        // Telegram's own pause window is not a broken library, and reporting it as one would invite
        // the user to retry into a longer wait.
        reason.startsWith("FLOOD_WAIT") || reason.contains("SLOWMODE") -> CloudFailure.Kind.RateLimited
        reason.contains("UNAUTHENTICATED") || reason.contains("AUTH") -> CloudFailure.Kind.NotAuthenticated
        else -> CloudFailure.Kind.RequestFailed
    }

    private fun TelegramRequestException.isMissingChat(): Boolean =
        reason.contains("CHAT_NOT_FOUND") || reason.contains("CHAT_ID_INVALID")

    private companion object {
        const val TYPE = "@type"

        /** Candidates with the right name are rare; 20 is generous and keeps start-up bounded. */
        const val SEARCH_LIMIT = 20

        const val MAX_PAGE = 100

        /** Message ids in a channel start at 1, so reaching it means the walk is finished. */
        const val FIRST_MESSAGE_ID = 1L

        const val EARLIEST_PROBE_ID = 100L
    }
}

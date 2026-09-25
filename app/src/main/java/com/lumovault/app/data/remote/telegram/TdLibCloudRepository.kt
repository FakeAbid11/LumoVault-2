package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudFailureException
import com.lumovault.app.domain.telegram.CloudHistoryPage
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import kotlinx.coroutines.CancellationException
import org.drinkless.tdlib.TdApi

/**
 * The cloud side of TDLib, spoken entirely through [TelegramClient]'s typed requests.
 *
 * Every request here is a *metadata* request. Reading history yields file sizes and remote
 * identifiers, never bytes, and no method below calls `downloadFile` — that is what keeps PRD
 * section 25's rule structural rather than a promise to behave.
 *
 * The request and field names come from `td/generate/scheme/td_api.tl` at the revision pinned in
 * `.github/workflows/build-tdlib.yml`. What that file says about `getChatHistory` in particular is
 * worth stating, because the usual tutorial text is wrong about it: `from_message_id` is a real
 * message identifier and `offset` is 0 or *negative*, so the walk below asks for each page by its
 * oldest message id rather than by a negated cursor.
 */
class TdLibCloudRepository(
    private val client: TelegramClient,
) : TelegramCloudRepository {
    override val isUsable: Boolean
        get() = client.isUsable

    override suspend fun accountUserId(): Long =
        request(TdApi.GetMe()).id.takeIf { it != NO_ID }
            ?: throw CloudFailureException(CloudFailure(CloudFailure.Kind.NotAuthenticated))

    /**
     * [TdApi.SearchChats] searches the chats TDLib already knows about this account — deliberately
     * *not* `searchChatsOnServer`, which queries Telegram's public directory and could return a
     * stranger's channel that merely shares the name.
     */
    override suspend fun findStorageChannel(): Long? =
        request(
            TdApi.SearchChats().apply {
                query = LumoVaultStorageProtocol.CHANNEL_TITLE
                typeFilter = TdApi.SearchChatTypeFilterChannel()
                limit = SEARCH_LIMIT
            },
        ).chatIds
            .firstOrNull { chatId -> validateChannel(chatId) is CloudChannelVerdict.Valid }

    /**
     * Name is only a candidate. A chat is adopted when it is a broadcast channel, this account owns
     * it, and the marker is present in its description or among its earliest messages.
     */
    override suspend fun validateChannel(chatId: Long): CloudChannelVerdict {
        val chat = try {
            client.request(TdApi.GetChat().apply { this.chatId = chatId })
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

        val supergroup = requestOrNull(TdApi.GetSupergroup().apply { this.supergroupId = supergroupId })
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
            TdApi.CreateNewSupergroupChat().apply {
                title = LumoVaultStorageProtocol.CHANNEL_TITLE
                isForum = false
                // TDLib has no ChatTypeChannel; a broadcast supergroup *is* a channel.
                isChannel = true
                description = LumoVaultStorageProtocol.markerText()
                // TDLib's own wording for "not a location-based chat" is a null location.
                location = null
                messageAutoDeleteTime = 0
                forImport = false
            },
        )
        val chatId = created.id
        if (chatId == NO_ID) {
            throw CloudFailureException(CloudFailure(CloudFailure.Kind.ChannelCreationFailed))
        }

        request(
            TdApi.SendMessage().apply {
                this.chatId = chatId
                // Topic, reply, options and markup are all documented as "pass null" when they do not
                // apply, which is the typed form of what the channel marker needs: a plain text post.
                topicId = null
                replyTo = null
                options = null
                replyMarkup = null
                inputMessageContent = TdApi.InputMessageText().apply {
                    text = TdApi.FormattedText().apply {
                        this.text = LumoVaultStorageProtocol.markerText()
                        entities = emptyArray()
                    }
                    linkPreviewOptions = null
                    clearDraft = false
                }
            },
        )
        return chatId
    }

    override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): CloudHistoryPage {
        val response = request(
            TdApi.GetChatHistory().apply {
                this.chatId = chatId
                this.fromMessageId = fromMessageId
                // 0, never negative: a negative offset asks TDLib for *newer* messages as well, and
                // the walk wants the next hundred going backwards.
                offset = 0
                // TDLib caps a page at 100 and returns fewer when it decides so; asking for more is
                // not an error, it just is not honoured.
                this.limit = limit.coerceIn(1, MAX_PAGE)
                // false: the whole point is to discover what is remote.
                onlyLocal = false
            },
        )

        val messages = response.messages.asList()
        val oldest = messages.mapNotNull { it.id.takeIf { id -> id != NO_ID } }.minOrNull()

        return CloudHistoryPage(
            media = messages.mapNotNull { TdCloudMapper.toCloudMedia(it, chatId) },
            oldestMessageId = oldest ?: 0,
            // History comes back newest-first, so the walk is over when the ids stop moving down or
            // the first message is reached — never when a page count says so.
            reachedBeginning = messages.isEmpty() || oldest == null || oldest <= FIRST_MESSAGE_ID,
            totalRemoteCount = response.totalCount.takeIf { it > 0 },
        )
    }

    /**
     * The marker is normally the channel's first message, and `getChatHistory` walks backwards from
     * a given id, so a page requested from [EARLIEST_PROBE_ID] returns the earliest messages a
     * channel can have. The description is checked first because it costs one request and does not
     * depend on message ids being dense.
     */
    private suspend fun markerVersion(chatId: Long, supergroupId: Long): Int? {
        val fullInfo = requestOrNull(TdApi.GetSupergroupFullInfo().apply { this.supergroupId = supergroupId })
        if (fullInfo != null) {
            TdCloudMapper.markerVersionInDescription(TdCloudMapper.descriptionOf(fullInfo))?.let { return it }
        }
        return probeHistory(chatId)
    }

    private suspend fun probeHistory(chatId: Long): Int? = try {
        val response = client.request(
            TdApi.GetChatHistory().apply {
                this.chatId = chatId
                fromMessageId = EARLIEST_PROBE_ID
                offset = 0
                limit = MAX_PAGE
                onlyLocal = false
            },
        )
        TdCloudMapper.markerVersionIn(response.messages.asList())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        null
    } catch (error: Exception) {
        throw CloudFailureException(CloudFailure(CloudFailure.Kind.RequestFailed), error)
    }

    /** A failed informational request means an absent field, not a failed validation. */
    private suspend fun <T : TdApi.Object> requestOrNull(function: TdApi.Function<T>): T? = try {
        client.request(function)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }

    private suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T = try {
        client.request(function)
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
        /** Candidates with the right name are rare; 20 is generous and keeps start-up bounded. */
        const val SEARCH_LIMIT = 20

        const val MAX_PAGE = 100

        /** Message ids in a channel start at 1, so reaching it means the walk is finished. */
        const val FIRST_MESSAGE_ID = 1L

        const val EARLIEST_PROBE_ID = 100L

        /** TDLib reserves 0 for "no identifier", for chats, messages and users alike. */
        const val NO_ID = 0L
    }
}

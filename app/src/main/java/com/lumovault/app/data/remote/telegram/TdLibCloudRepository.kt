package com.lumovault.app.data.remote.telegram

import android.util.Log
import com.lumovault.app.domain.telegram.ChannelDiscovery
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
    /**
     * Waits between discovery rounds. A parameter rather than a direct call to `delay` so the bounded retry
     * can be tested at the speed of the assertion rather than the speed of Telegram.
     */
    private val pause: suspend (Long) -> Unit = { milliseconds -> kotlinx.coroutines.delay(milliseconds) },
) : TelegramCloudRepository {
    override val isUsable: Boolean
        get() = client.isUsable

    override suspend fun accountUserId(): Long =
        request(TdApi.GetMe()).id.takeIf { it != NO_ID }
            ?: throw CloudFailureException(CloudFailure(CloudFailure.Kind.NotAuthenticated))

    /**
     * Finds the account's storage channel across a bounded number of rounds, and reports absence only when
     * it has actually established it.
     *
     * Two lookups, for two different caches. `searchChats` is TDLib's offline search over the chats it
     * already knows — which on a fresh installation, where TDLib's database went away with the app's own
     * files, is nothing at all. `searchChatsOnServer` asks Telegram to search this account's own chats over
     * the network, and that is what makes a reinstall recoverable. It is emphatically not
     * `searchPublicChats`, which is the internet's channel directory and could answer with a stranger's chat
     * that merely shares the name; nothing here calls it. Between the two, `loadChats` drives TDLib's chat
     * list forward from the server, and its documented 404 is the only answer that says the list is complete.
     *
     * So an empty round is evidence of nothing. Saying so — [ChannelDiscovery.InProgress] — is this method's
     * whole job, until either a channel turns up or the list is finished *and* the server answered cleanly in
     * the same round. That pair is the only thing allowed to report [ChannelDiscovery.Absent], because it is
     * the only pair that cannot be explained by a slow network.
     */
    override suspend fun discoverStorageChannel(): ChannelDiscovery {
        Log.i(TAG, "CLOUD_CHANNEL_DISCOVERY_STARTED")
        var telegramFailed = false

        for (round in 1..DISCOVERY_ROUNDS) {
            val listComplete = pumpChatList()
            val candidates = askForCandidates()
            if (!candidates.answered) telegramFailed = true

            val valid = candidates.chatIds.filter { chatId ->
                val verdict = validateChannel(chatId)
                if (verdict is CloudChannelVerdict.Valid) Log.i(TAG, "CLOUD_CHANNEL_CANDIDATE_FOUND chat_id=$chatId")
                verdict is CloudChannelVerdict.Valid
            }

            if (valid.isNotEmpty()) {
                val chosen = mostPopulated(valid)
                Log.i(TAG, "CLOUD_CHANNEL_VALIDATED chat_id=$chosen alternates=${valid.size - 1}")
                return ChannelDiscovery.Found(chatId = chosen, alternates = valid.size - 1)
            }

            if (listComplete && candidates.answered) {
                // Both, in the same round: a complete local list says nothing about what the server holds,
                // and a clean server search says nothing about a list still being paged in.
                Log.i(TAG, "CLOUD_CHANNEL_ABSENT round=$round")
                return ChannelDiscovery.Absent
            }

            Log.i(TAG, "CLOUD_CHANNEL_DISCOVERY_RETRY round=$round list_complete=$listComplete")
            if (round < DISCOVERY_ROUNDS) pause(ROUND_PAUSE_MILLIS)
        }

        return ChannelDiscovery.InProgress(
            if (telegramFailed) ChannelDiscovery.Reason.TelegramUnreachable
            else ChannelDiscovery.Reason.ChatListLoading,
        )
    }

    /** True when TDLib says there is nothing left to load, which is the completion signal discovery needs. */
    private suspend fun pumpChatList(): Boolean = try {
        val request = TdApi.LoadChats()
        // null is TDLib's own spelling of "the main chat list"; the archive and the filter lists cannot hold
        // a channel this account created.
        request.chatList = null
        request.limit = CHAT_PAGE
        client.request(request)
        false
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        // TDLib answers 404 once a chat list holds nothing more. Any other error is a real failure, and a
        // real failure is not evidence that a channel does not exist.
        if (error.code == ALL_CHATS_LOADED_CODE) {
            true
        } else {
            throw CloudFailureException(CloudFailure(CloudFailure.Kind.RequestFailed, error.code), error)
        }
    }

    /**
     * The chats both caches can name, and whether the server side of the question was actually answered.
     *
     * A failed server search is not turned into an exception: the offline search may still find the channel
     * on a warm cache, and the round has to be able to say "inconclusive" rather than end the sync.
     */
    private suspend fun askForCandidates(): Candidates {
        val server = search(onServer = true)
        val offline = search(onServer = false)
        return Candidates(
            chatIds = (server.chatIds + offline.chatIds).distinct().take(SEARCH_LIMIT),
            answered = server.answered,
        )
    }

    /**
     * One search, from either cache.
     *
     * The two requests are built separately rather than through a shared `if` expression because Kotlin's
     * common supertype of them is TDLib's `Function`, which has none of the fields — a `query` assigned to
     * that is a compile error, and an untyped builder would have to go back through a cast to say it.
     */
    private suspend fun search(onServer: Boolean): Candidates = try {
        val ids = if (onServer) {
            val query = TdApi.SearchChatsOnServer()
            query.query = LumoVaultStorageProtocol.CHANNEL_TITLE
            query.typeFilter = TdApi.SearchChatTypeFilterChannel()
            query.limit = SEARCH_LIMIT
            client.request(query).chatIds
        } else {
            val query = TdApi.SearchChats()
            query.query = LumoVaultStorageProtocol.CHANNEL_TITLE
            query.typeFilter = TdApi.SearchChatTypeFilterChannel()
            query.limit = SEARCH_LIMIT
            client.request(query).chatIds
        }

        Candidates(ids.toList(), answered = true)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        Candidates(emptyList(), answered = false)
    }

    /**
     * Which of several valid channels is the storage one.
     *
     * The channel holding messages is the channel holding the backups; an empty second one — which is
     * exactly what the bug this replaces used to create — has nothing to compare. TDLib's
     * `supergroupFullInfo` carries no message count at the pinned revision, so the size comes from where a
     * scan reads it: `total_count` from a one-message page. Ties fall to the smaller chat id, so the answer
     * is the same on every run. Nothing here deletes, migrates or renames the channel that is not chosen; it
     * stays in the account, reachable, exactly as it was.
     */
    private suspend fun mostPopulated(candidates: List<Long>): Long {
        if (candidates.size == 1) return candidates.first()

        val sized = candidates.map { chatId -> chatId to historySize(chatId) }
        var best = sized.first()
        for (candidate in sized.drop(1)) {
            if (candidate.second > best.second ||
                (candidate.second == best.second && candidate.first < best.first)
            ) {
                best = candidate
            }
        }
        return best.first
    }

    private suspend fun historySize(chatId: Long): Int = try {
        client.request(chatHistory(chatId, NEWEST_MESSAGE_ID, 1)).totalCount
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        // An unreadable channel does not win a comparison; a candidate with evidence beats speculation.
        0
    }

    /**
     * Name is only a candidate. A chat is adopted when it is a broadcast channel, this account owns
     * it, and the marker is present in its description or among its earliest messages.
     */
    override suspend fun validateChannel(chatId: Long): CloudChannelVerdict {
        val chat = try {
            client.request(getChat(chatId))
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

        val supergroup = requestOrNull(getSupergroup(supergroupId)) ?: return CloudChannelVerdict.NotFound
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

        request(sendMarker(chatId))
        return chatId
    }

    override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): CloudHistoryPage {
        val response = request(chatHistory(chatId, fromMessageId, limit.coerceIn(1, MAX_PAGE)))

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
        val fullInfo = requestOrNull(supergroupFullInfo(supergroupId))
        if (fullInfo != null) {
            TdCloudMapper.markerVersionInDescription(TdCloudMapper.descriptionOf(fullInfo))?.let { return it }
        }
        return probeHistory(chatId)
    }

    private suspend fun probeHistory(chatId: Long): Int? = try {
        val response = client.request(chatHistory(chatId, EARLIEST_PROBE_ID, MAX_PAGE))
        TdCloudMapper.markerVersionIn(response.messages.asList())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: TelegramRequestException) {
        null
    } catch (error: Exception) {
        throw CloudFailureException(CloudFailure(CloudFailure.Kind.RequestFailed), error)
    }

    /**
     * Request builders below assign field by field rather than through `apply`.
     *
     * TDLib's generated field names are the same words this class uses for its own parameters —
     * `chatId`, `fromMessageId`, `supergroupId` — and inside an `apply` block both are in scope for the
     * right-hand side. A statement with an explicit receiver cannot quietly read the field it is
     * writing, which is the only kind of mistake here that would compile, pass, and scan the wrong
     * page forever.
     */
    private fun getChat(chatId: Long): TdApi.GetChat {
        val request = TdApi.GetChat()
        request.chatId = chatId
        return request
    }

    private fun getSupergroup(supergroupId: Long): TdApi.GetSupergroup {
        val request = TdApi.GetSupergroup()
        request.supergroupId = supergroupId
        return request
    }

    private fun supergroupFullInfo(supergroupId: Long): TdApi.GetSupergroupFullInfo {
        val request = TdApi.GetSupergroupFullInfo()
        request.supergroupId = supergroupId
        return request
    }

    private fun chatHistory(chatId: Long, fromMessageId: Long, limit: Int): TdApi.GetChatHistory {
        val request = TdApi.GetChatHistory()
        request.chatId = chatId
        request.fromMessageId = fromMessageId
        // 0, never negative: a negative offset asks TDLib for *newer* messages as well, and the walk
        // wants the next hundred going backwards.
        request.offset = 0
        // TDLib caps a page at 100 and returns fewer when it decides so; asking for more is not an
        // error, it just is not honoured.
        request.limit = limit
        // false: the whole point is to discover what is remote.
        request.onlyLocal = false
        return request
    }

    private fun sendMarker(chatId: Long): TdApi.SendMessage {
        val text = TdApi.FormattedText()
        text.text = LumoVaultStorageProtocol.markerText()
        text.entities = emptyArray()

        val content = TdApi.InputMessageText()
        content.text = text
        content.linkPreviewOptions = null
        content.clearDraft = false

        val request = TdApi.SendMessage()
        request.chatId = chatId
        // Topic, reply, options and markup are all documented as "pass null" when they do not apply,
        // which is the typed form of what the channel marker needs: a plain text post.
        request.topicId = null
        request.replyTo = null
        request.options = null
        request.replyMarkup = null
        request.inputMessageContent = content
        return request
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

        /**
         * How many rounds discovery may spend waiting for TDLib's chat list before it gives up without
         * concluding anything. Six half-second rounds is three seconds of patience on a cold install, and a
         * ceiling on how long the Cloud screen can sit in "searching" — waiting instead until the list is
         * genuinely complete is unbounded on a large account, and an unbounded wait is the same screen.
         */
        const val DISCOVERY_ROUNDS = 6

        const val ROUND_PAUSE_MILLIS = 500L

        /** Chats per `loadChats` call. TDLib may answer with fewer; that is its choice, not a stop signal. */
        const val CHAT_PAGE = 100

        /** TDLib's documented answer once a chat list holds nothing more to load. */
        const val ALL_CHATS_LOADED_CODE = 404

        /** `from_message_id` 0 starts a history read at the newest message. */
        const val NEWEST_MESSAGE_ID = 0L

        /** Chat identifiers only — never a title, a path or a TDLib object. */
        const val TAG = "LumoVaultCloudChannel"

        const val MAX_PAGE = 100

        /** Message ids in a channel start at 1, so reaching it means the walk is finished. */
        const val FIRST_MESSAGE_ID = 1L

        const val EARLIEST_PROBE_ID = 100L

        /** TDLib reserves 0 for "no identifier", for chats, messages and users alike. */
        const val NO_ID = 0L
    }
}

/**
 * One round's candidate list, and whether the server was actually able to answer.
 *
 * The second field is what makes absence provable: a search that failed and a search that found nothing
 * return the same empty list, and only one of them is a fact about the account.
 */
internal data class Candidates(val chatIds: List<Long>, val answered: Boolean)

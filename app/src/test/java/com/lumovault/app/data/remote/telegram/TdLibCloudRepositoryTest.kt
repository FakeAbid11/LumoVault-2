package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.ChannelDiscovery
import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudFailureException
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import kotlinx.coroutines.runBlocking
import org.drinkless.tdlib.TdApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Channel discovery, validation and the history walk, driven by the request objects LumoVault actually
 * sends.
 *
 * Two of these rules are worth the fixtures they cost. A channel is adopted only on evidence — the
 * name, the ownership and the marker — because adopting a stranger's channel that happens to share the
 * name would read their messages into this library. And the history cursor is a *positive* message id
 * with a non-negative offset, which is what TDLib's own scheme says and the opposite of the
 * negative-cursor spelling that circulates in tutorials; get that wrong and a scan re-reads the newest
 * hundred messages forever without ever complaining.
 */
class TdLibCloudRepositoryTest {
    private val client = FakeTelegramClient()

    /** Recorded instead of slept, so a bounded retry is asserted at the speed of the assertion. */
    private val pauses = mutableListOf<Long>()

    private val repository = TdLibCloudRepository(client, pause = { pauses += it })

    private fun photoMessage(id: Long, remoteId: String): TdApi.Message {
        val message = TdApi.Message()
        message.id = id
        message.date = 1758768000
        message.content = TdApi.MessagePhoto().apply {
            photo = TdApi.Photo().apply {
                sizes = arrayOf(
                    TdApi.PhotoSize().apply {
                        type = "x"
                        width = 160
                        height = 120
                        photo = remoteFile("PREVIEW_$remoteId")
                    },
                    TdApi.PhotoSize().apply {
                        type = "i"
                        width = 4032
                        height = 3024
                        photo = remoteFile(remoteId)
                    },
                )
            }
        }
        return message
    }

    @Test
    fun `the account id comes from getMe and a missing one is not a session`() {
        client.answer = { TdApi.User().apply { id = 4242L } }
        assertEquals(4242L, runBlocking { repository.accountUserId() })

        client.answer = { TdApi.User() }
        val failure = runBlocking {
            runCatching { repository.accountUserId() }.exceptionOrNull()
        } as CloudFailureException
        assertEquals(CloudFailure.Kind.NotAuthenticated, failure.failure.kind)
    }

    /**
     * The six cases discovery exists to get right. The one that matters most is the third: a lookup that
     * answered nothing because TDLib had not loaded the account's chats yet, which used to be read as
     * "this account has no storage channel" and answered by creating one — after which the association
     * points at the empty channel and every photograph the user backed up stays in the old one.
     */
    @Test
    fun `discovery asks the server for the account's own channels and verifies before it adopts`() {
        val stranger = 11L
        val mine = 22L

        client.answer = { function ->
            when {
                function is TdApi.SearchChatsOnServer -> {
                    assertEquals(LumoVaultStorageProtocol.CHANNEL_TITLE, function.query)
                    assertTrue(
                        "only channels may match, so a private chat cannot be adopted",
                        function.typeFilter is TdApi.SearchChatTypeFilterChannel,
                    )
                    chats(stranger, mine)
                }

                // The offline cache is empty, which is the reinstall case; the server answer is what has
                // to be enough to find the channel.
                function is TdApi.SearchChats -> chats()
                function is TdApi.LoadChats -> TdApi.Ok()

                function is TdApi.GetChat && function.chatId == stranger ->
                    chat(stranger, LumoVaultStorageProtocol.CHANNEL_TITLE, supergroupId = 7)

                function is TdApi.GetChat && function.chatId == mine ->
                    chat(mine, LumoVaultStorageProtocol.CHANNEL_TITLE, supergroupId = 9)

                function is TdApi.GetSupergroup && function.supergroupId == 7L ->
                    supergroupOwnedBy(TdApi.ChatMemberStatusMember())

                function is TdApi.GetSupergroup && function.supergroupId == 9L ->
                    supergroupOwnedBy(TdApi.ChatMemberStatusCreator())

                function is TdApi.GetSupergroupFullInfo -> markerInfo()

                else -> TdApi.Ok()
            }
        }

        // The first candidate carries the right name and belongs to someone else; the second is ours, so
        // discovery must walk past the impostor rather than adopt it.
        assertEquals(
            ChannelDiscovery.Found(chatId = mine, alternates = 0),
            runBlocking { repository.discoverStorageChannel() },
        )
        assertEquals(
            "the stranger's channel was examined, not adopted",
            1,
            client.sent.count { it is TdApi.GetSupergroup && it.supergroupId == 7L },
        )
        assertTrue(
            "an answer was found, so nothing was created",
            client.sent.none { it is TdApi.CreateNewSupergroupChat },
        )
    }

    @Test
    fun `a chat list that has not finished loading is retried rather than answered with a new channel`() {
        val mine = 22L
        var serverRounds = 0

        client.answer = { function ->
            when {
                function is TdApi.SearchChatsOnServer -> {
                    serverRounds += 1
                    if (serverRounds < 2) chats() else chats(mine)
                }

                function is TdApi.SearchChats -> chats()
                function is TdApi.LoadChats -> TdApi.Ok()
                function is TdApi.GetChat -> chat(mine, LumoVaultStorageProtocol.CHANNEL_TITLE, supergroupId = 9)
                function is TdApi.GetSupergroup -> supergroupOwnedBy(TdApi.ChatMemberStatusCreator())
                function is TdApi.GetSupergroupFullInfo -> markerInfo()
                else -> TdApi.Ok()
            }
        }

        assertEquals(
            ChannelDiscovery.Found(chatId = mine, alternates = 0),
            runBlocking { repository.discoverStorageChannel() },
        )
        assertEquals("the empty round waited before asking again", 1, pauses.size)
        assertTrue(client.sent.none { it is TdApi.CreateNewSupergroupChat })
    }

    @Test
    fun `a loaded list and a clean server answer are the only thing that concludes absence`() {
        client.answer = { function ->
            when {
                // TDLib's documented end-of-chat-list answer.
                function is TdApi.LoadChats -> throw TelegramRequestException(code = 404, reason = "ALL_CHATS_LOADED")
                function is TdApi.SearchChatsOnServer -> chats()
                function is TdApi.SearchChats -> chats()
                else -> TdApi.Ok()
            }
        }

        assertEquals(ChannelDiscovery.Absent, runBlocking { repository.discoverStorageChannel() })
        assertEquals("a concluded search does not wait", 0, pauses.size)
    }

    @Test
    fun `a server search that fails is never read as a channel that does not exist`() {
        client.answer = { function ->
            when {
                function is TdApi.LoadChats -> throw TelegramRequestException(code = 404, reason = "ALL_CHATS_LOADED")
                function is TdApi.SearchChatsOnServer ->
                    throw TelegramRequestException(code = 420, reason = "FLOOD_WAIT_30")

                function is TdApi.SearchChats -> chats()
                else -> TdApi.Ok()
            }
        }

        assertEquals(
            "Telegram did not answer, which is not the same as there being nothing to find",
            ChannelDiscovery.InProgress(ChannelDiscovery.Reason.TelegramUnreachable),
            runBlocking { repository.discoverStorageChannel() },
        )
        assertEquals(
            "every round without a conclusion waited; the last one had nothing left to wait for",
            5,
            pauses.size,
        )
    }

    @Test
    fun `a chat list still loading gives up as inconclusive and never as absent`() {
        client.answer = { function ->
            when {
                function is TdApi.LoadChats -> TdApi.Ok()
                function is TdApi.SearchChatsOnServer -> chats()
                function is TdApi.SearchChats -> chats()
                else -> TdApi.Ok()
            }
        }

        assertEquals(
            ChannelDiscovery.InProgress(ChannelDiscovery.Reason.ChatListLoading),
            runBlocking { repository.discoverStorageChannel() },
        )
    }

    @Test
    fun `a loadChats failure that is not the end of the list is a cloud failure, not absence`() {
        client.answer = { function ->
            when {
                function is TdApi.LoadChats -> throw TelegramRequestException(code = 500, reason = "INTERNAL")
                else -> chats()
            }
        }

        val failure = runCatching { runBlocking { repository.discoverStorageChannel() } }.exceptionOrNull()
        assertTrue(failure is CloudFailureException)
        assertEquals(CloudFailure.Kind.RequestFailed, (failure as CloudFailureException).failure.kind)
        assertEquals(500, failure.failure.statusCode)
    }

    @Test
    fun `two valid channels resolve to the one holding the backups and neither is disturbed`() {
        val emptySecond = 21L
        val oldLibrary = 22L

        client.answer = { function ->
            when {
                function is TdApi.SearchChatsOnServer -> chats(emptySecond, oldLibrary)
                function is TdApi.SearchChats -> chats()
                function is TdApi.LoadChats -> TdApi.Ok()
                function is TdApi.GetSupergroup -> supergroupOwnedBy(TdApi.ChatMemberStatusCreator())
                function is TdApi.GetSupergroupFullInfo -> markerInfo()

                function is TdApi.GetChat -> chat(
                    function.chatId,
                    LumoVaultStorageProtocol.CHANNEL_TITLE,
                    supergroupId = function.chatId,
                )

                // The older channel holds 412 messages; the one an errant build created holds none.
                function is TdApi.GetChatHistory -> TdApi.Messages().apply {
                    totalCount = if (function.chatId == oldLibrary) 412 else 0
                    messages = emptyArray()
                }

                else -> TdApi.Ok()
            }
        }

        assertEquals(
            ChannelDiscovery.Found(chatId = oldLibrary, alternates = 1),
            runBlocking { repository.discoverStorageChannel() },
        )
        assertTrue(
            "choosing between two channels never deletes or creates one",
            client.sent.none { it is TdApi.CreateNewSupergroupChat || it is TdApi.DeleteChatHistory },
        )
    }

    /** TDLib's answer to a chat search: the ids, in the order it found them. */
    private fun chats(vararg ids: Long): TdApi.Chats {
        val chats = TdApi.Chats()
        chats.chatIds = ids
        chats.totalCount = ids.size
        return chats
    }

    /** A description carrying this build's marker, which is the cheapest way a channel proves itself. */
    private fun markerInfo(version: Int = LumoVaultStorageProtocol.VERSION): TdApi.SupergroupFullInfo {
        val info = TdApi.SupergroupFullInfo()
        info.description = LumoVaultStorageProtocol.markerText(version = version)
        return info
    }

    @Test
    fun `a channel named LumoVault Backup that is not ours is never adopted`() {
        val chatId = 55L

        fun verdictFor(
            type: TdApi.ChatType,
            supergroup: TdApi.Supergroup?,
            fullInfo: TdApi.SupergroupFullInfo?,
        ) = runBlocking {
            client.answer = { function ->
                when {
                    function is TdApi.GetChat -> {
                        val chat = TdApi.Chat()
                        chat.id = chatId
                        chat.title = LumoVaultStorageProtocol.CHANNEL_TITLE
                        chat.type = type
                        chat
                    }

                    function is TdApi.GetSupergroup && supergroup != null -> supergroup
                    function is TdApi.GetSupergroupFullInfo && fullInfo != null -> fullInfo

                    // The marker probe walks history when the description carries none, so a page must
                    // come back even when the test is only looking at the description.
                    function is TdApi.GetChatHistory ->
                        TdApi.Messages().apply { messages = emptyArray() }

                    else -> TdApi.Ok()
                }
            }
            repository.validateChannel(chatId)
        }

        val channelType = TdApi.ChatTypeSupergroup().apply {
            supergroupId = 9
            isChannel = true
        }

        assertEquals(
            "a basic group is not a channel",
            CloudChannelVerdict.NotAChannel,
            verdictFor(
                type = TdApi.ChatTypeBasicGroup().apply { basicGroupId = 3 },
                supergroup = null,
                fullInfo = null,
            ),
        )

        assertEquals(
            "a group is not a channel",
            CloudChannelVerdict.NotAChannel,
            verdictFor(
                type = TdApi.ChatTypeSupergroup().apply {
                    supergroupId = 9
                    isChannel = false
                },
                supergroup = null,
                fullInfo = null,
            ),
        )

        assertEquals(
            "a channel this account did not create cannot be its storage",
            CloudChannelVerdict.NotOwned,
            verdictFor(
                type = channelType,
                supergroup = supergroupOwnedBy(TdApi.ChatMemberStatusMember()),
                fullInfo = null,
            ),
        )

        assertEquals(
            "a channel with no marker is just a channel with our name",
            CloudChannelVerdict.MarkerMissing,
            verdictFor(
                type = channelType,
                supergroup = supergroupOwnedBy(TdApi.ChatMemberStatusCreator()),
                fullInfo = TdApi.SupergroupFullInfo().apply { description = "photo dumps" },
            ),
        )

        assertEquals(
            CloudChannelVerdict.MarkerTooNew(9),
            verdictFor(
                type = channelType,
                supergroup = supergroupOwnedBy(TdApi.ChatMemberStatusAdministrator()),
                fullInfo = TdApi.SupergroupFullInfo().apply {
                    description = LumoVaultStorageProtocol.markerText(version = 9)
                },
            ),
        )

        assertEquals(
            CloudChannelVerdict.Valid,
            verdictFor(
                type = channelType,
                supergroup = supergroupOwnedBy(TdApi.ChatMemberStatusCreator()),
                fullInfo = TdApi.SupergroupFullInfo().apply {
                    description = LumoVaultStorageProtocol.markerText()
                },
            ),
        )
    }

    @Test
    fun `a deleted channel is reported as absent so discovery can replace it`() {
        client.answer = { throw TelegramRequestException(code = 400, reason = "CHAT_ID_INVALID") }

        assertEquals(
            CloudChannelVerdict.NotFound,
            runBlocking { repository.validateChannel(9L) },
        )
    }

    @Test
    fun `the marker is written to the description and as the first message`() {
        val createdId = 77L

        client.answer = { function ->
            when (function) {
                is TdApi.CreateNewSupergroupChat -> {
                    assertEquals(LumoVaultStorageProtocol.CHANNEL_TITLE, function.title)
                    assertTrue(function.isChannel)
                    assertEquals(LumoVaultStorageProtocol.markerText(), function.description)
                    assertNull("an ordinary channel has no location", function.location)
                    TdApi.Chat().apply { id = createdId }
                }

                else -> TdApi.Ok()
            }
        }

        assertEquals(createdId, runBlocking { repository.createStorageChannel() })

        val sent = client.sentOf<TdApi.SendMessage>().single()
        assertEquals(createdId, sent.chatId)
        val marker = (sent.inputMessageContent as TdApi.InputMessageText).text.text
        assertEquals(LumoVaultStorageProtocol.markerText(), marker)
        assertEquals("a channel marker is a post, not a reply", null, sent.replyTo)
    }

    @Test
    fun `a chat id that came back unusable is not silently turned into a new channel`() {
        assertEquals(
            CloudChannelVerdict.NotAChannel,
            runBlocking {
                client.answer = {
                    TdApi.Chat().apply {
                        id = 5L
                        title = "somewhere else entirely"
                        type = TdApi.ChatTypePrivate().apply { userId = 8 }
                    }
                }
                repository.validateChannel(5L)
            },
        )
    }

    @Test
    fun `the history walk asks for the next older page by message id, not by a negated cursor`() {
        var requested: TdApi.GetChatHistory? = null

        client.answer = { function ->
            if (function is TdApi.GetChatHistory) {
                requested = function
                TdApi.Messages().apply {
                    totalCount = 3
                    messages = arrayOf(photoMessage(9L, "A"), photoMessage(8L, "B"))
                }
            } else {
                TdApi.Ok()
            }
        }

        val page = runBlocking { repository.loadHistoryPage(chatId = 5, fromMessageId = 9L, limit = 100) }
        val history = requested!!

        assertEquals(5L, history.chatId)
        assertEquals(9L, history.fromMessageId)
        assertEquals(0, history.offset)
        assertFalse("only_local false is what lets the scan see remote media", history.onlyLocal)
        assertEquals(100, history.limit)

        assertEquals(2, page.media.size)
        assertEquals(MediaType.Photo, page.media.first().type)
        assertEquals(8L, page.oldestMessageId)
        assertEquals(3, page.totalRemoteCount)
        assertEquals(5L, page.media.first().chatId)
        assertEquals("PREVIEW_B", page.media.last().previewRemoteFileId)
    }

    @Test
    fun `a page limit TDLib does not honour is clamped rather than argued with`() {
        var asked = 0

        client.answer = { function ->
            if (function is TdApi.GetChatHistory) {
                asked = function.limit
                TdApi.Messages().apply { messages = emptyArray() }
            } else {
                TdApi.Ok()
            }
        }

        runBlocking { repository.loadHistoryPage(5L, 9L, limit = 5000) }
        assertEquals(100, asked)

        runBlocking { repository.loadHistoryPage(5L, 9L, limit = 0) }
        assertEquals("a page must ask for at least one message", 1, asked)
    }

    @Test
    fun `reaching the first message ends the walk and an empty page ends it too`() {
        client.answer = {
            TdApi.Messages().apply { messages = arrayOf(photoMessage(2L, "A"), photoMessage(1L, "B")) }
        }
        val lastPage = runBlocking { repository.loadHistoryPage(5L, 2L, 100) }
        assertTrue(lastPage.reachedBeginning)
        assertEquals(1L, lastPage.oldestMessageId)

        client.answer = { TdApi.Messages().apply { messages = emptyArray() } }
        val empty = runBlocking { repository.loadHistoryPage(5L, 2L, 100) }
        assertTrue("a page with nothing in it cannot be paged further", empty.reachedBeginning)
        assertNull(empty.media.firstOrNull())
    }

    @Test
    fun `a message with no media is skipped rather than indexed as an empty row`() {
        val text = TdApi.Message().apply {
            id = 4L
            date = 1
            content = TdApi.MessageText().apply {
                text = TdApi.FormattedText().apply { this.text = "a note, not a photo" }
            }
        }

        client.answer = {
            TdApi.Messages().apply { messages = arrayOf(text, photoMessage(3L, "P")) }
        }

        val page = runBlocking { repository.loadHistoryPage(5L, 4L, 100) }

        assertEquals(1, page.media.size)
        assertEquals(3L, page.media.single().messageId)
        assertEquals("the walk still advances past a skipped message", 3L, page.oldestMessageId)
    }

    @Test
    fun `a Telegram error becomes a cloud failure with LumoVault's own kind`() {
        client.answer = { throw TelegramRequestException(code = 420, reason = "FLOOD_WAIT_60") }

        val failure = runBlocking {
            runCatching { repository.accountUserId() }.exceptionOrNull()
        } as CloudFailureException

        assertEquals(CloudFailure.Kind.RateLimited, failure.failure.kind)
        assertEquals(420, failure.failure.statusCode)
    }

    @Test
    fun `an unconfigured build says so instead of throwing`() {
        val unusable = TdLibCloudRepository(FakeTelegramClient(usable = false))

        assertFalse(unusable.isUsable)
    }
}

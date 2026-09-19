package com.lumovault.lumovault.core.tdlib

import com.lumovault.lumovault.features.settings.data.SettingsRepository
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves (or creates) the private channel used as backup storage.
 *
 * Ported from the Flutter backup engine's channel bootstrap. The channel is
 * the user's own, created in their account under
 * [TdLibConfig.STORAGE_CHANNEL_NAME]; its id is cached in settings so later
 * runs skip the lookup. A cached id is *verified* against [TdApi.GetChat]
 * before use — the user may have deleted the channel, and a stale id would
 * fail every upload with CHAT_NOT_FOUND until the cache was cleared.
 */
@Singleton
class StorageChannelService @Inject constructor(
    private val client: TdLibClient,
    private val settingsRepository: SettingsRepository,
) {

    /**
     * Returns the storage channel id, creating the channel on first run.
     *
     * Order of resolution: verify the cached id → search the chat list →
     * search the server by title → create. Creating before searching would
     * duplicate the channel every time the chat list lagged behind.
     */
    suspend fun resolveOrCreate(): Long {
        settingsRepository.load().storageChannelId?.let { cached ->
            val chat = runCatching { client.send(TdApi.GetChat(cached)) as TdApi.Chat }.getOrNull()
            if (chat != null && isStorageChannel(chat)) return cached
        }

        findExisting()?.let { id ->
            persist(id)
            return id
        }

        val created = client.send(
            TdApi.CreateNewSupergroupChat().apply {
                title = TdLibConfig.STORAGE_CHANNEL_NAME
                isForum = false
                isChannel = true
                description = TdLibConfig.STORAGE_CHANNEL_DESCRIPTION
            },
        ) as TdApi.Chat
        persist(created.id)
        return created.id
    }

    /** True when [chat] is a channel with the expected storage title. */
    private fun isStorageChannel(chat: TdApi.Chat): Boolean =
        chat.title == TdLibConfig.STORAGE_CHANNEL_NAME &&
            chat.type is TdApi.ChatTypeSupergroup &&
            (chat.type as TdApi.ChatTypeSupergroup).isChannel

    /**
     * Looks for an existing storage channel: first the chats TDLib has cached
     * locally, then a server-side title search (a channel created on another
     * device is not in the local cache until it has been listed once).
     */
    private suspend fun findExisting(): Long? {
        runCatching {
            val chats = client.send(TdApi.GetChats().apply { limit = LOCAL_SCAN_LIMIT }) as TdApi.Chats
            for (id in chats.chatIds) {
                val chat = client.send(TdApi.GetChat(id)) as TdApi.Chat
                if (isStorageChannel(chat)) return id
            }
        }
        runCatching {
            val found = client.send(
                TdApi.SearchChatsOnServer().apply {
                    query = TdLibConfig.STORAGE_CHANNEL_NAME
                    limit = LOCAL_SCAN_LIMIT
                },
            ) as TdApi.Chats
            for (id in found.chatIds) {
                val chat = client.send(TdApi.GetChat(id)) as TdApi.Chat
                if (isStorageChannel(chat)) return id
            }
        }
        return null
    }

    private suspend fun persist(channelId: Long) {
        settingsRepository.update { it.copy(storageChannelId = channelId) }
    }

    private companion object {
        const val LOCAL_SCAN_LIMIT = 100
    }
}
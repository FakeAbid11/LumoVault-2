package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelUpdateListener @Inject constructor(
    private val client: TdLibClient,
    private val settingsRepository: SettingsRepository,
    private val metadataRepository: MetadataRepository,
    private val downloader: TelegramMetadataDownloader,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerJob: Job? = null

    private val channelId: Long
        get() = settingsRepository.load().storageChannelId ?: 0L

    fun startListening() {
        if (listenerJob?.isActive == true) return

        listenerJob = scope.launch {
            client.updates.collect { update ->
                when (update) {
                    is TdLibClient.Update.NewMessage -> handleNewMessage(update.message)
                    is TdLibClient.Update.MessageContentChanged -> handleContentChanged(update.chatId, update.messageId, update.newContent)
                    is TdLibClient.Update.MessagesDeleted -> handleMessagesDeleted(update.chatId, update.messageIds)
                    else -> {}
                }
            }
        }
    }

    fun stopListening() {
        listenerJob?.cancel()
        listenerJob = null
    }

    private fun handleNewMessage(message: TdApi.Message) {
        if (message.chatId != channelId) return

        val caption = extractCaption(message.content) ?: return
        when {
            caption.startsWith(TelegramMetadataDownloader.MANIFEST_PREFIX) -> {
                scope.launch {
                    reconcileFromRemote()
                }
            }
            caption.startsWith(TelegramMetadataDownloader.PARTITION_PREFIX) -> {
                scope.launch {
                    reconcileFromRemote()
                }
            }
        }
    }

    private fun handleContentChanged(chatId: Long, messageId: Long, newContent: TdApi.MessageContent) {
        if (chatId != channelId) return

        val caption = extractCaption(newContent) ?: return
        if (caption.startsWith(TelegramMetadataDownloader.MANIFEST_PREFIX) ||
            caption.startsWith(TelegramMetadataDownloader.PARTITION_PREFIX)
        ) {
            scope.launch {
                reconcileFromRemote()
            }
        }
    }

    private fun handleMessagesDeleted(chatId: Long, messageIds: LongArray) {
        if (chatId != channelId) return
    }

    private fun extractCaption(content: TdApi.MessageContent): String? {
        return when (content) {
            is TdApi.MessageDocument -> content.caption?.text
            is TdApi.MessagePhoto -> content.caption?.text
            is TdApi.MessageText -> content.text?.text
            else -> null
        }
    }

    private suspend fun reconcileFromRemote() {
        try {
            metadataRepository.reconcileFromTelegram(
                downloadManifest = { downloader.downloadManifest() },
                downloadPartition = { partitionId -> downloader.downloadPartition(partitionId) },
            )
        } catch (_: Throwable) {}
    }
}

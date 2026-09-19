package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramMetadataDownloader @Inject constructor(
    private val client: TdLibClient,
    private val settingsRepository: SettingsRepository,
) {

    private val channelId: Long
        get() = settingsRepository.load().storageChannelId ?: 0L

    suspend fun downloadManifest(): String? = withContext(Dispatchers.IO) {
        if (channelId == 0L) return@withContext null

        val messages = searchMessages(MANIFEST_PREFIX) ?: return@withContext null
        val manifestMessage = messages.firstOrNull() ?: return@withContext null
        downloadDocumentContent(manifestMessage)
    }

    suspend fun downloadPartition(partitionId: String): String? = withContext(Dispatchers.IO) {
        if (channelId == 0L) return@withContext null

        val query = "$PARTITION_PREFIX$partitionId"
        val messages = searchMessages(query) ?: return@withContext null
        val partitionMessage = messages.firstOrNull() ?: return@withContext null
        downloadDocumentContent(partitionMessage)
    }

    private suspend fun searchMessages(query: String): List<TdApi.Message>? {
        return try {
            val result = client.send(
                TdApi.SearchChatMessages().apply {
                    chatId = channelId
                    this.query = query
                    limit = 10
                    sender = null
                    fromMessageId = 0
                    offset = 0
                    filter = null
                },
            ) as TdApi.Messages

            result.messages.toList()
        } catch (_: Throwable) {
            null
        }
    }

    private suspend fun downloadDocumentContent(message: TdApi.Message): String? {
        val content = message.content
        if (content !is TdApi.MessageDocument) return null

        val document = content.document
        val local = document.document.local

        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
            return try {
                java.io.File(local.path).readText()
            } catch (_: Throwable) { null }
        }

        try {
            client.send(TdApi.DownloadFile().apply {
                fileId = document.document.id
                priority = 16
            })

            val fileUpdate = kotlinx.coroutines.withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                var downloaded = false
                client.updates.collect { update ->
                    if (update is TdLibClient.Update.FileUpdated && update.file.id == document.document.id) {
                        if (update.file.local.isDownloadingCompleted) {
                            downloaded = true
                            return@collect
                        }
                    }
                }
                downloaded
            }

            if (fileUpdate == true) {
                val updatedFile = client.send(TdApi.GetFile().apply {
                    fileId = document.document.id
                }) as TdApi.File

                if (updatedFile.local.path.isNotEmpty()) {
                    return java.io.File(updatedFile.local.path).readText()
                }
            }
        } catch (_: Throwable) {}

        return null
    }

    companion object {
        const val MANIFEST_PREFIX = "[LV:manifest]"
        const val PARTITION_PREFIX = "[LV:partition:"
        const val DOWNLOAD_TIMEOUT_MS = 60_000L
    }
}

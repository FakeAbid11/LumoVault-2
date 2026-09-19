package com.lumovault.lumovault.features.metadata.data.service

import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramMetadataUploader @Inject constructor(
    private val client: TdLibClient,
    private val settingsRepository: SettingsRepository,
) {

    private val channelId: Long
        get() = settingsRepository.load().storageChannelId ?: 0L

    suspend fun uploadPartition(partitionId: String, json: String) = withContext(Dispatchers.IO) {
        if (channelId == 0L) throw IllegalStateException("Storage channel not configured")

        val tempFile = File.createTempFile("partition_${partitionId}_", ".json", File("/tmp"))
        try {
            tempFile.writeText(json)

            val content = TdApi.InputMessageDocument().apply {
                document = TdApi.InputFileLocal().apply { path = tempFile.absolutePath }
                caption = TdApi.FormattedText().apply {
                    text = "[LV:partition:$partitionId]"
                }
            }

            val result = client.send(
                TdApi.SendMessage().apply {
                    chatId = channelId
                    messageThreadId = 0
                    replyToMessageId = 0
                    options = TdApi.MessageSendOptions().apply {
                        disableNotification = true
                        fromBackground = true
                    }
                    inputMessageContent = content
                },
            ) as TdApi.Message

            result.id
        } finally {
            tempFile.delete()
        }
    }

    suspend fun uploadManifest(manifestJson: String) = withContext(Dispatchers.IO) {
        if (channelId == 0L) throw IllegalStateException("Storage channel not configured")

        val tempFile = File.createTempFile("manifest_", ".json", File("/tmp"))
        try {
            tempFile.writeText(manifestJson)

            val content = TdApi.InputMessageDocument().apply {
                document = TdApi.InputFileLocal().apply { path = tempFile.absolutePath }
                caption = TdApi.FormattedText().apply {
                    text = "[LV:manifest]"
                }
            }

            val result = client.send(
                TdApi.SendMessage().apply {
                    chatId = channelId
                    messageThreadId = 0
                    replyToMessageId = 0
                    options = TdApi.MessageSendOptions().apply {
                        disableNotification = true
                        fromBackground = true
                    }
                    inputMessageContent = content
                },
            ) as TdApi.Message

            result.id
        } finally {
            tempFile.delete()
        }
    }
}

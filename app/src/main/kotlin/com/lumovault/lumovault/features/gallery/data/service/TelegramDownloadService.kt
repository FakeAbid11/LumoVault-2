package com.lumovault.lumovault.features.gallery.data.service

import android.content.Context
import android.net.Uri
import com.lumovault.lumovault.core.tdlib.TdLibClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelegramDownloadService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TdLibClient,
) {

    data class DownloadProgress(
        val taskId: String,
        val progress: Float,
        val bytesDownloaded: Long,
        val totalBytes: Long,
    )

    data class DownloadResult(
        val taskId: String,
        val filePath: String,
    )

    private val _progress = MutableSharedFlow<DownloadProgress>(extraBufferCapacity = 64)
    val progress: SharedFlow<DownloadProgress> = _progress.asSharedFlow()

    private val activeDownloads = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<String?>>()

    suspend fun downloadFile(
        chatId: Long,
        messageId: Long,
        taskId: String = "$chatId_$messageId",
    ): DownloadResult? {
        activeDownloads[taskId]?.let { existing ->
            val path = existing.await()
            return path?.let { DownloadResult(taskId, it) }
        }

        val deferred = kotlinx.coroutines.CompletableDeferred<String?>()
        activeDownloads[taskId] = deferred

        try {
            val message = client.send(TdApi.GetMessage(chatId, messageId)) as TdApi.Message
            val remoteFile = extractFile(message.content) ?: return null

            if (remoteFile.local.isDownloadingCompleted && remoteFile.local.path.isNotEmpty()) {
                deferred.complete(remoteFile.local.path)
                return DownloadResult(taskId, remoteFile.local.path)
            }

            val downloadJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    client.send(TdApi.DownloadFile().apply {
                        fileId = remoteFile.id
                        priority = 16
                    })
                } catch (_: Throwable) {
                    deferred.complete(null)
                }
            }

            withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                client.updates.collect { update ->
                    if (update is TdLibClient.Update.FileUpdated && update.file.id == remoteFile.id) {
                        val local = update.file.local
                        val progress = if (update.file.expectedSize > 0) {
                            local.downloadedSize.toFloat() / update.file.expectedSize
                        } else 0f

                        _progress.tryEmit(
                            DownloadProgress(
                                taskId = taskId,
                                progress = progress,
                                bytesDownloaded = local.downloadedSize,
                                totalBytes = update.file.expectedSize,
                            ),
                        )

                        if (local.isDownloadingCompleted && local.path.isNotEmpty()) {
                            deferred.complete(local.path)
                            downloadJob.cancel()
                            return@collect
                        }
                    }
                }
            }

            val path = deferred.await()
            return path?.let { DownloadResult(taskId, it) }
        } catch (_: Throwable) {
            deferred.complete(null)
            return null
        } finally {
            activeDownloads.remove(taskId)
        }
    }

    fun cancel(taskId: String) {
        activeDownloads.remove(taskId)?.complete(null)
    }

    fun cancelAll() {
        activeDownloads.values.forEach { it.complete(null) }
        activeDownloads.clear()
    }

    private fun extractFile(content: TdApi.MessageContent): TdApi.File? {
        return when (content) {
            is TdApi.MessagePhoto -> {
                val sizes = content.photo.sizes
                sizes.maxByOrNull { it.photo.expectedSize }?.photo
            }
            is TdApi.MessageVideo -> content.video.video
            is TdApi.MessageDocument -> content.document.document
            is TdApi.MessageAnimation -> content.animation.animation
            is TdApi.MessageSticker -> content.sticker.sticker
            is TdApi.MessageVideoNote -> content.videoNote.video
            is TdApi.MessageAudio -> content.audio.audio
            else -> null
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.launch(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): kotlinx.coroutines.Job {
        return kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob()).launch { block() }
    }

    companion object {
        const val DOWNLOAD_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

package com.lumovault.lumovault.features.restore.data.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.net.toUri
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.MediaStatus
import com.lumovault.lumovault.core.tdlib.StorageChannelService
import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.core.tdlib.TdLibException
import com.lumovault.lumovault.features.metadata.domain.model.CaptionMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The restore engine: scans the storage channel, downloads media, and rebuilds
 * the local catalog.
 *
 * Ported from lib/features/restore/engine/restore_engine.dart, including the
 * defect fixed there: the original "stored" the fetched metadata manifest by
 * self-assigning a local variable, so a restore never actually applied the
 * remote catalog. Here the caption data is written straight into the media
 * row — the only assignment that can be a no-op is the dedup skip, which is
 * deliberate.
 *
 * Cross-app compatibility: media captions are the [CaptionMetadata] JSON
 * documented in docs/WIRE-FORMAT.md, so a backup written by the Flutter app
 * restores here, and vice versa. Documents named metadata/ (manifests and
 * partitions) are skipped when collecting media.
 */
@Singleton
class RestoreEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TdLibClient,
    private val storageChannelService: StorageChannelService,
    private val mediaDao: MediaDao,
) {

    /** One media message found in the channel. */
    data class RestoreItem(
        val chatId: Long,
        val messageId: Long,
        val caption: CaptionMetadata?,
        val fileName: String,
        val mimeType: String,
        val file: TdApi.File,
        val dateMs: Long,
    )

    /** Scans the channel and returns media items, oldest first. */
    suspend fun scanChannel(onProgress: (Int) -> Unit = {}): List<RestoreItem> {
        val chatId = storageChannelService.resolveOrCreate()
        val items = ArrayList<RestoreItem>()
        var fromMessageId = 0L
        while (true) {
            val messages = client.send(
                TdApi.GetChatHistory().apply {
                    this.chatId = chatId
                    this.fromMessageId = fromMessageId
                    offset = 0
                    limit = SCAN_PAGE
                    onlyLocal = false
                },
            ) as TdApi.Messages
            val page = messages.messages ?: break
            if (page.isEmpty()) break
            for (message in page) {
                message.toRestoreItem()?.let { items.add(it) }
            }
            onProgress(items.size)
            fromMessageId = page.last().id
            if (page.size < SCAN_PAGE) break
        }
        // GetChatHistory returns newest-first; restore in capture order.
        return items.sortedBy { it.caption?.createdAt?.toEpochMilli() ?: it.dateMs }
    }

    private fun TdApi.Message.toRestoreItem(): RestoreItem? {
        val content = content
        return when (content) {
            is TdApi.MessageDocument -> {
                val doc = content.document
                if (doc?.document == null) return null
                // Metadata documents are the catalog, not the library.
                if (doc.fileName.startsWith(METADATA_PATH_PREFIX)) return null
                RestoreItem(
                    chatId = chatId,
                    messageId = id,
                    caption = content.caption?.text?.let { CaptionMetadata.fromCaptionString(it) },
                    fileName = doc.fileName.ifBlank { "media" },
                    mimeType = doc.mimeType.ifBlank { "application/octet-stream" },
                    file = doc.document,
                    dateMs = date * 1000L,
                )
            }
            is TdApi.MessagePhoto -> {
                // Photos sent as photos (e.g. from another Telegram client)
                // are the largest available size; they were compressed by the
                // sender, so they restore at that quality.
                val file = content.photo?.sizes?.maxByOrNull { it.width * it.height }?.photo
                    ?: return null
                RestoreItem(
                    chatId = chatId,
                    messageId = id,
                    caption = content.caption?.text?.let { CaptionMetadata.fromCaptionString(it) },
                    fileName = "photo_$id.jpg",
                    mimeType = "image/jpeg",
                    file = file,
                    dateMs = date * 1000L,
                )
            }
            else -> null
        }
    }

    /**
     * Downloads one item and returns a readable local file. TDLib's own copy
     * (inside its files dir) is returned directly — the catalog rebuild only
     * needs a path, and duplicating gigabytes into MediaStore would double
     * the restore time and storage.
     */
    suspend fun download(item: RestoreItem): File {
        val file = item.file
        if (file.local?.isDownloadingCompleted == true && file.local.path.isNotBlank()) {
            val existing = File(file.local.path)
            if (existing.exists()) return existing
        }
        val requested = client.send(
            TdApi.DownloadFile().apply {
                fileId = file.id
                priority = DOWNLOAD_PRIORITY
                offset = 0
                limit = 0
            },
        ) as TdApi.File
        val completed = withTimeout(DOWNLOAD_TIMEOUT_MS) {
            client.updates.first { update ->
                update is TdLibClient.Update.FileUpdated &&
                    update.file.id == requested.id &&
                    update.file.local?.isDownloadingCompleted == true
            }.let { it as TdLibClient.Update.FileUpdated }.file
        }
        val path = completed.local?.path
            ?: throw TdLibException(code = "DOWNLOAD_FAILED", message = "Download completed without a path")
        return File(path)
    }

    /**
     * Rebuilds the catalog row for one downloaded item: matches an existing
     * row by content hash (a device that already has the photo keeps its row
     * and gains the Telegram linkage), otherwise inserts a cloud-sourced row.
     */
    suspend fun rebuildRow(item: RestoreItem, localFile: File, fileHash: String) {
        val now = System.currentTimeMillis()
        val caption = item.caption
        val existing = if (fileHash.isNotEmpty()) mediaDao.byHash(fileHash) else null
        if (existing != null) {
            mediaDao.markUploaded(
                localId = existing.localId,
                status = MediaStatus.uploaded,
                messageId = item.messageId.toString(),
                fileId = item.file.remote?.uniqueId ?: item.file.remote?.id,
                uploadedAt = now,
                backedUpAt = caption?.backedUpAt?.toEpochMilli() ?: now,
            )
            return
        }

        val mediaItemId = caption?.mediaItemId?.takeIf { it.isNotBlank() }
            ?: "restored_${item.messageId}"
        val createdAt = caption?.createdAt?.toEpochMilli() ?: item.dateMs
        val entity = MediaItemEntity(
            localId = mediaItemId,
            fileHash = fileHash,
            telegramMessageId = item.messageId.toString(),
            telegramFileId = item.file.remote?.uniqueId ?: item.file.remote?.id,
            filePath = localFile.absolutePath,
            fileName = item.fileName,
            mimeType = caption?.mimeType ?: item.mimeType,
            fileSize = item.file.size,
            width = caption?.width ?: 0,
            height = caption?.height ?: 0,
            durationMs = caption?.durationMs?.toLong(),
            createdAt = createdAt,
            modifiedAt = caption?.modifiedAt?.toEpochMilli() ?: createdAt,
            scannedAt = now,
            uploadedAt = now,
            backedUpAt = caption?.backedUpAt?.toEpochMilli() ?: now,
            status = MediaStatus.uploaded,
            isFavorite = caption?.isFavorite ?: false,
            isHidden = caption?.isHidden ?: false,
            isArchived = caption?.isArchived ?: false,
            isTrashed = caption?.isTrashed ?: false,
            trashedAt = caption?.trashedAt?.toEpochMilli(),
            albumName = caption?.albumName,
            deviceFolder = caption?.deviceFolder,
            description = caption?.description,
            tags = caption?.tags ?: emptyList(),
            isDateUserSet = caption?.isDateUserSet ?: false,
        )
        mediaDao.upsertAll(listOf(entity))
    }

    /**
     * Exports one downloaded file into the user-visible gallery (Pictures or
     * Movies / LumoVault). Best-effort: a catalog row referencing TDLib's
     * private copy is still functional inside the app.
     */
    suspend fun exportToGallery(item: RestoreItem, source: File): Uri? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (item.mimeType.startsWith("video/")) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, GALLERY_DIR)
                }
                context.contentResolver.insert(collection, values)?.let { uri ->
                    context.contentResolver.openOutputStream(uri)?.use { output ->
                        source.inputStream().use { it.copyTo(output) }
                    }
                    uri
                }
            } else {
                val base = Environment.getExternalStoragePublicDirectory(
                    if (item.mimeType.startsWith("video/")) {
                        Environment.DIRECTORY_MOVIES
                    } else {
                        Environment.DIRECTORY_PICTURES
                    },
                )
                val dir = File(base, "LumoVault").apply { mkdirs() }
                val target = File(dir, item.fileName)
                source.copyTo(target, overwrite = true)
                target.toUri()
            }
        }.getOrNull()
    }

    companion object {
        private const val SCAN_PAGE = 100
        private const val DOWNLOAD_PRIORITY = 1
        private const val METADATA_PATH_PREFIX = "metadata/"
        private const val GALLERY_DIR = "Pictures/LumoVault"
        /** 30 minutes per file ceiling, mirroring the upload timeout. */
        private const val DOWNLOAD_TIMEOUT_MS = 30L * 60 * 1000
    }
}
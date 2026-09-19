package com.lumovault.lumovault.features.backup.data.service

import android.content.Context
import android.net.Uri
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.MediaStatus
import com.lumovault.lumovault.core.tdlib.StorageChannelService
import com.lumovault.lumovault.core.tdlib.TdLibClient
import com.lumovault.lumovault.core.tdlib.TdLibConfig
import com.lumovault.lumovault.core.tdlib.TdLibException
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import com.lumovault.lumovault.features.metadata.domain.model.CaptionMetadata
import com.lumovault.lumovault.features.settings.data.SettingsRepository
import com.lumovault.lumovault.features.settings.domain.model.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** One engine pass, summarized for the worker / dashboard. */
data class BackupRunResult(
    val processed: Int,
    val uploaded: Int,
    val deduped: Int,
    val failed: Int,
    val bytesUploaded: Long,
    val finishedAt: Long = System.currentTimeMillis(),
)

/**
 * The upload engine: drains pending media rows into the storage channel.
 *
 * Ported from lib/features/backup/engine/backup_engine.dart, with the typed
 * TDLib client dissolving the original's message-id correlation. Deliberate
 * carryovers and fixes:
 *
 *  - **Uploads are documents, never photos** — [TdApi.InputMessagePhoto]
 *    would let Telegram recompress; the original-quality guarantee is the
 *    whole product. (The Flutter engine made the same choice.)
 *  - **Caption carries the metadata** — the [CaptionMetadata] JSON is the
 *    restore contract documented in docs/WIRE-FORMAT.md; a restore parses it
 *    back, so albums/flags/timeline survive on a second device.
 *  - **De-selected media is not uploaded** — the Flutter engine kept
 *    uploading tasks the user had de-selected (PORT-STATUS "defects" list);
 *    here folder and media-type filters are applied at pick-up time and the
 *    filtered rows are marked excluded.
 *  - **Content-hash dedup** — a hash already uploaded under another row skips
 *    the transfer and links to the existing message, as in the original.
 *
 * Files are materialized to a temp copy in the app cache before upload,
 * because TDLib's [TdApi.InputFileLocal] needs a real filesystem path and
 * scoped storage gives us content URIs.
 */
@Singleton
class BackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: TdLibClient,
    private val storageChannelService: StorageChannelService,
    private val mediaDao: MediaDao,
    private val settingsRepository: SettingsRepository,
    private val scanner: MediaScannerService,
) {

    /** Set by the dashboard's manual Start; overrides [AppSettings.autoBackupEnabled]. */
    @Volatile
    var manualRunRequested: Boolean = false
        private set

    fun requestManualRun() {
        manualRunRequested = true
    }

    /**
     * Runs one pass: uploads up to the configured batch size of pending items.
     * Returns normally on success; throws on a fatal setup failure (not
     * authenticated), which the worker maps to retry/failure.
     */
    suspend fun runOnce(): BackupRunResult {
        if (!client.isAuthenticated()) {
            throw TdLibException(
                code = "NOT_AUTHENTICATED",
                message = "Telegram is not signed in",
                displayMessage = "Sign in to Telegram in Settings before starting a backup.",
            )
        }

        val settings = settingsRepository.load()
        if (!settings.autoBackupEnabled && !manualRunRequested) {
            return BackupRunResult(0, 0, 0, 0, 0)
        }
        manualRunRequested = false

        val channelId = storageChannelService.resolveOrCreate()

        var uploaded = 0
        var deduped = 0
        var failed = 0
        var bytes = 0L
        var processed = 0

        val batch = mediaDao.pendingForBackup(MediaStatus.pending, settings.uploadBatchSize)
        for (item in batch) {
            if (shouldSkip(item, settings)) {
                // Mark excluded so the item is not re-picked every run — the
                // original's "kept uploading de-selected tasks" defect.
                mediaDao.setExcluded(item.localId, true)
                continue
            }

            processed++
            mediaDao.markUploading(item.localId, MediaStatus.uploading)
            try {
                val hash = ensureHash(item)
                if (hash == null) {
                    // Unreadable file: fail the row, don't spin on it forever.
                    mediaDao.setStatus(item.localId, MediaStatus.failed, "File could not be read")
                    failed++
                    continue
                }

                val existing = mediaDao.firstUploadedByHash(hash, MediaStatus.uploaded)
                val now = System.currentTimeMillis()
                if (existing != null) {
                    mediaDao.markUploaded(
                        localId = item.localId,
                        status = MediaStatus.uploaded,
                        messageId = existing.telegramMessageId,
                        fileId = existing.telegramFileId,
                        uploadedAt = now,
                        backedUpAt = now,
                    )
                    deduped++
                } else {
                    val tempFile = materialize(item)
                    try {
                        val (messageId, remoteFileId) = upload(channelId, item, hash, tempFile)
                        mediaDao.markUploaded(
                            localId = item.localId,
                            status = MediaStatus.uploaded,
                            messageId = messageId,
                            fileId = remoteFileId,
                            uploadedAt = now,
                            backedUpAt = now,
                        )
                        uploaded++
                        bytes += item.fileSize
                    } finally {
                        tempFile.delete()
                    }
                }
            } catch (e: Throwable) {
                mediaDao.setStatus(item.localId, MediaStatus.failed, e.message ?: e.javaClass.simpleName)
                failed++
            }
            // Gentle pacing, as configured; keeps Telegram's rate limits happy
            // on large initial backups.
            if (settings.uploadDelayMs > 0) delay(settings.uploadDelayMs.toLong())
        }

        if (uploaded > 0 || deduped > 0) {
            settingsRepository.update { it.copy(lastBackupAt = System.currentTimeMillis()) }
        }
        return BackupRunResult(processed, uploaded, deduped, failed, bytes)
    }

    /** True when the item is filtered out by the current settings. */
    private fun shouldSkip(item: MediaItemEntity, settings: AppSettings): Boolean {
        val isVideo = item.mimeType.startsWith("video/")
        if (isVideo && !settings.backupVideos) return true
        if (!isVideo && !settings.backupPhotos) return true
        if (item.fileSize > TdLibConfig.MAX_FILE_SIZE_BYTES) return true
        if (settings.maxFileSizeBytes > 0 && item.fileSize > settings.maxFileSizeBytes) return true
        if (settings.excludedFolders.any { it == item.deviceFolder || it == item.albumName }) return true
        // When the user opted into specific folders only, everything outside
        // them is out of scope (empty selection = everything).
        if (settings.includedFolders.isNotEmpty() &&
            settings.includedFolders.none { it == item.deviceFolder || it == item.albumName }
        ) {
            return true
        }
        return false
    }

    /** Hashes the row's content if not already hashed, persisting the result. */
    private suspend fun ensureHash(item: MediaItemEntity): String? {
        if (item.fileHash.isNotEmpty()) return item.fileHash
        val hash = scanner.hashUri(Uri.parse(item.filePath))
        if (hash.isEmpty()) return null
        mediaDao.upsertAll(listOf(item.copy(fileHash = hash)))
        return hash
    }

    /**
     * Copies the item's content into a cache temp file. TDLib streams the file
     * itself, so this is a plain blocking copy on IO.
     */
    private suspend fun materialize(item: MediaItemEntity): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, TEMP_DIR).apply { mkdirs() }
        val temp = File(dir, "${item.localId}_${item.fileName.ifBlank { "upload" }}")
        context.contentResolver.openInputStream(Uri.parse(item.filePath))?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        } ?: throw TdLibException(code = "FILE_NOT_FOUND", message = "Media no longer readable: ${item.localId}")
        temp
    }

    /**
     * Sends the document and waits for Telegram's send-succeeded update.
     *
     * [TdApi.SendMessage] replies with the *ephemeral* message; the final id
     * arrives via [TdLibClient.Update.MessageSendSucceeded], whose
     * `oldMessageId` matches the ephemeral one. Large files legitimately take
     * minutes, hence the generous ceiling.
     */
    private suspend fun upload(
        channelId: Long,
        item: MediaItemEntity,
        hash: String,
        tempFile: File,
    ): Pair<String, String> {
        val content = TdApi.InputMessageDocument().apply {
            document = TdApi.InputDocument().apply {
                document = TdApi.InputFileLocal().apply { path = tempFile.absolutePath }
                disableContentTypeDetection = false
            }
            caption = TdApi.FormattedText().apply {
                text = buildCaption(item, hash)
                entities = arrayOf<TdApi.TextEntity>()
            }
        }
        val sent = client.send(
            TdApi.SendMessage().apply {
                chatId = channelId
                inputMessageContent = content
            },
        ) as TdApi.Message

        val finalMessage = withTimeout(UPLOAD_TIMEOUT_MS) {
            client.updates.first { update ->
                when (update) {
                    is TdLibClient.Update.MessageSendSucceeded -> update.oldMessageId == sent.id
                    is TdLibClient.Update.MessageSendFailed ->
                        if (update.oldMessageId == sent.id) throw TdLibException.from(update.error) else false
                    else -> false
                }
            }.let { it as TdLibClient.Update.MessageSendSucceeded }.message
        }

        val document = (finalMessage.content as? TdApi.MessageDocument)?.document
        return Pair(
            finalMessage.id.toString(),
            document?.document?.remote?.uniqueId
                ?: document?.document?.remote?.id
                ?: finalMessage.id.toString(),
        )
    }

    /**
     * Builds the caption wire format (docs/WIRE-FORMAT.md). The Flutter app
     * parses exactly this, and vice versa — this is what makes a library
     * portable across both apps.
     */
    private fun buildCaption(item: MediaItemEntity, hash: String): String {
        val now = Instant.now()
        return CaptionMetadata(
            mediaItemId = item.localId,
            fileHash = hash,
            createdAt = Instant.ofEpochMilli(item.createdAt),
            modifiedAt = Instant.ofEpochMilli(item.modifiedAt),
            backedUpAt = now,
            mimeType = item.mimeType,
            fileSize = item.fileSize,
            width = item.width,
            height = item.height,
            durationMs = item.durationMs?.toInt(),
            isFavorite = item.isFavorite,
            isHidden = item.isHidden,
            isArchived = item.isArchived,
            isTrashed = false, // trashed items are never enqueued
            albumName = item.albumName,
            deviceFolder = item.deviceFolder,
            description = item.description,
            tags = item.tags,
            isDateUserSet = item.isDateUserSet,
            customFields = null,
        ).toCaptionString()
    }

    companion object {
        private const val TEMP_DIR = "backup_upload"
        /** 30 minutes: a 2 GB upload on a slow connection is within scope. */
        private const val UPLOAD_TIMEOUT_MS = 30L * 60 * 1000
    }
}
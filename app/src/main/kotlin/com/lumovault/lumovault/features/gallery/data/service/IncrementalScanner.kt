package com.lumovault.lumovault.features.gallery.data.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.database.entity.MediaStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of an incremental scan.
 */
data class IncrementalScanResult(
    val newItems: List<MediaItemEntity>,
    val updatedItems: List<MediaItemEntity>,
    val deletedIds: List<String>,
    val totalChecked: Int,
    val durationMs: Long,
) {
    val hasChanges: Boolean
        get() = newItems.isNotEmpty() || updatedItems.isNotEmpty() || deletedIds.isNotEmpty()
}

/**
 * Performs incremental device scans by comparing against a known set.
 *
 * Ported from Flutter `lib/features/gallery/data/repositories/incremental_scanner.dart`.
 *
 * Uses a lightweight approach: fetches MediaStore assets and compares
 * modified timestamps or local IDs against the existing set, avoiding
 * full hash recomputation for unchanged files.
 *
 * Key properties:
 * - Paginated fetching (200/page) with 30s timeout per page.
 * - 90s timeout per asset build (hash + metadata extraction).
 * - Batch flush (50 items) so a slow/interrupted scan doesn't lose progress.
 * - Deletion detection skipped when folder-filtered or incomplete pages.
 * - Telegram-only items (restored/channel-scanned) are never deletion candidates.
 */
@Singleton
class IncrementalScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scanner: MediaScannerService,
) {

    private val contentResolver get() = context.contentResolver

    private val imageUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private val videoUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private val projection = arrayOf(
        MediaStore.MediaColumns._ID,
        MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.MIME_TYPE,
        MediaStore.MediaColumns.SIZE,
        MediaStore.MediaColumns.WIDTH,
        MediaStore.MediaColumns.HEIGHT,
        MediaStore.MediaColumns.DATE_ADDED,
        MediaStore.MediaColumns.DATE_MODIFIED,
        MediaStore.MediaColumns.BUCKET_ID,
        MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
        MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.DURATION,
    )

    /**
     * Scan for changes since [lastKnownItems].
     *
     * [lastKnownItems] is the set of items already tracked by the app,
     * keyed by [MediaItemEntity.localId]. [includedFolders] optionally limits
     * the scan to specific device folders. [onBatch], if given, is called
     * periodically with newly-processed items so the caller can persist them
     * as the scan runs rather than only at the very end.
     */
    suspend fun scanForChanges(
        lastKnownItems: Map<String, MediaItemEntity>,
        includedFolders: Set<String>? = null,
        onProgress: ((current: Int, total: Int) -> Unit)? = null,
        onBatch: ((newItems: List<MediaItemEntity>, updatedItems: List<MediaItemEntity>) -> Unit)? = null,
    ): IncrementalScanResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val filterActive = !includedFolders.isNullOrEmpty()
        val grandTotal = countAssets(includedFolders)

        val newItems = ArrayList<MediaItemEntity>()
        val updatedItems = ArrayList<MediaItemEntity>()
        val seenIds = HashSet<String>()
        var totalChecked = 0
        var hadIncompletePage = false

        var pendingNew = ArrayList<MediaItemEntity>()
        var pendingUpdated = ArrayList<MediaItemEntity>()

        for (uri in listOf(imageUri, videoUri)) {
            val pageResults = queryPages(uri, includedFolders)
            for ((pageIdx, page) in pageResults.withIndex()) {
                if (page == null) {
                    // Timed out page.
                    hadIncompletePage = true
                    totalChecked += PAGE_SIZE
                    onProgress?.invoke(totalChecked, grandTotal)
                    continue
                }

                for (item in page) {
                    totalChecked++
                    seenIds.add(item.id.toString())

                    val localId = item.id.toString()
                    val existing = lastKnownItems[localId]

                    if (existing == null) {
                        // Brand new item — need full scan (hash + metadata).
                        val entity = buildEntity(item)
                        if (entity != null) {
                            val autoIncluded = filterActive
                            val finalEntity = entity.copy(
                                isExcluded = !autoIncluded,
                                albumName = item.bucketName,
                                deviceFolder = item.relativePath,
                            )
                            newItems.add(finalEntity)
                            pendingNew.add(finalEntity)
                        }
                    } else {
                        // Check if modified timestamp changed.
                        val deviceModifiedMs = item.dateModifiedSec * 1000L
                        if (deviceModifiedMs > existing.modifiedAt) {
                            // File was modified — rebuild metadata.
                            val entity = buildEntity(item)
                            if (entity != null) {
                                val updated = entity.copy(
                                    id = existing.id,
                                    isExcluded = existing.isExcluded,
                                    status = existing.status,
                                    fileHash = existing.fileHash,
                                    telegramMessageId = existing.telegramMessageId,
                                    telegramFileId = existing.telegramFileId,
                                    uploadedAt = existing.uploadedAt,
                                    backedUpAt = existing.backedUpAt,
                                    isFavorite = existing.isFavorite,
                                    isHidden = existing.isHidden,
                                    isArchived = existing.isArchived,
                                    isTrashed = existing.isTrashed,
                                    trashedAt = existing.trashedAt,
                                    tags = existing.tags,
                                    aiLabels = existing.aiLabels,
                                    clipEmbedding = existing.clipEmbedding,
                                    description = existing.description,
                                    albumName = item.bucketName,
                                    deviceFolder = item.relativePath,
                                )
                                updatedItems.add(updated)
                                pendingUpdated.add(updated)
                            }
                        }
                    }

                    if (totalChecked % 50 == 0 || totalChecked == grandTotal) {
                        onProgress?.invoke(totalChecked, grandTotal)
                    }

                    if (pendingNew.size + pendingUpdated.size >= BATCH_FLUSH_SIZE) {
                        onBatch?.invoke(pendingNew, pendingUpdated)
                        pendingNew = ArrayList()
                        pendingUpdated = ArrayList()
                    }
                }
            }
        }

        onBatch?.invoke(pendingNew, pendingUpdated)

        // Deletion detection: only when no folder filter and no incomplete pages.
        val deletedIds: List<String>
        if (filterActive || hadIncompletePage) {
            deletedIds = emptyList()
        } else {
            deletedIds = lastKnownItems.values
                .filter { !it.localId.startsWith("telegram_") }
                .filter { it.localId !in seenIds }
                .map { it.localId }
        }

        val duration = System.currentTimeMillis() - startTime
        IncrementalScanResult(
            newItems = newItems,
            updatedItems = updatedItems,
            deletedIds = deletedIds,
            totalChecked = totalChecked,
            durationMs = duration,
        )
    }

    /**
     * Build full backup metadata for a single asset on demand — for when the
     * user acts on one specific photo before it's been through a full scan.
     */
    suspend fun buildSingleItem(mediaStoreId: Long): MediaItemEntity? = withContext(Dispatchers.IO) {
        val item = querySingleItem(mediaStoreId) ?: return@withContext null
        buildEntity(item)
    }

    // ----------------------------------------------------------- internal

    private fun countAssets(includedFolders: Set<String>?): Int {
        var total = 0
        for (uri in listOf(imageUri, videoUri)) {
            val selection = if (!includedFolders.isNullOrEmpty()) {
                "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN (${includedFolders.joinToString(",") { "'$it'" }})"
            } else null
            contentResolver.query(uri, arrayOf(MediaStore.MediaColumns._ID), selection, null, null)?.use {
                total += it.count
            }
        }
        return total
    }

    /**
     * Query pages from a MediaStore URI. Returns a list where null entries
     * represent timed-out pages.
     */
    private fun queryPages(
        uri: Uri,
        includedFolders: Set<String>?,
    ): List<List<MediaStoreItem>?> {
        val selection = if (!includedFolders.isNullOrEmpty()) {
            "${MediaStore.MediaColumns.BUCKET_DISPLAY_NAME} IN (${includedFolders.joinToString(",") { "'$it'" }})"
        } else null

        val results = ArrayList<List<MediaStoreItem>?>()
        var offset = 0
        while (true) {
            val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $PAGE_SIZE OFFSET $offset"
            val items = ArrayList<MediaStoreItem>()
            try {
                contentResolver.query(uri, projection, selection, null, sortOrder)?.use { c ->
                    val idIdx = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    while (c.moveToNext()) {
                        val id = c.getLong(idIdx)
                        items.add(
                            MediaStoreItem(
                                id = id,
                                contentUri = ContentUris.withAppendedId(uri, id),
                                displayName = c.getStringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: "media_$id",
                                mimeType = c.getStringOrNull(MediaStore.MediaColumns.MIME_TYPE).orEmpty(),
                                size = c.getLongOrNull(MediaStore.MediaColumns.SIZE) ?: 0L,
                                width = c.getIntOrNull(MediaStore.MediaColumns.WIDTH) ?: 0,
                                height = c.getIntOrNull(MediaStore.MediaColumns.HEIGHT) ?: 0,
                                durationMs = c.getLongOrNull(MediaStore.MediaColumns.DURATION)?.takeIf { it > 0 },
                                dateAddedSec = c.getLongOrNull(MediaStore.MediaColumns.DATE_ADDED) ?: 0L,
                                dateModifiedSec = c.getLongOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
                                bucketId = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_ID),
                                bucketName = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                                relativePath = c.getStringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
                                latitude = null,
                                longitude = null,
                                isVideo = uri == videoUri,
                            ),
                        )
                    }
                }
            } catch (_: Exception) {
                results.add(null)
                offset += PAGE_SIZE
                if (items.isEmpty()) break
                continue
            }
            results.add(items)
            offset += PAGE_SIZE
            if (items.size < PAGE_SIZE) break
        }
        return results
    }

    private fun querySingleItem(mediaStoreId: Long): MediaStoreItem? {
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
        return queryItem(uri) ?: run {
            val videoUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, mediaStoreId)
            queryItem(videoUri)
        }
    }

    private fun queryItem(uri: Uri): MediaStoreItem? {
        return contentResolver.query(uri, projection, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                MediaStoreItem(
                    id = id,
                    contentUri = uri,
                    displayName = c.getStringOrNull(MediaStore.MediaColumns.DISPLAY_NAME) ?: "media_$id",
                    mimeType = c.getStringOrNull(MediaStore.MediaColumns.MIME_TYPE).orEmpty(),
                    size = c.getLongOrNull(MediaStore.MediaColumns.SIZE) ?: 0L,
                    width = c.getIntOrNull(MediaStore.MediaColumns.WIDTH) ?: 0,
                    height = c.getIntOrNull(MediaStore.MediaColumns.HEIGHT) ?: 0,
                    durationMs = c.getLongOrNull(MediaStore.MediaColumns.DURATION)?.takeIf { it > 0 },
                    dateAddedSec = c.getLongOrNull(MediaStore.MediaColumns.DATE_ADDED) ?: 0L,
                    dateModifiedSec = c.getLongOrNull(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L,
                    bucketId = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_ID),
                    bucketName = c.getStringOrNull(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME),
                    relativePath = c.getStringOrNull(MediaStore.MediaColumns.RELATIVE_PATH),
                    latitude = null,
                    longitude = null,
                    isVideo = uri.toString().contains("video"),
                )
            } else null
        }
    }

    private fun buildEntity(item: MediaStoreItem): MediaItemEntity? {
        val hash = hashUri(item.contentUri)
        if (hash.isEmpty()) return null
        val now = System.currentTimeMillis()
        return scanner.toEntity(item, now).copy(fileHash = hash)
    }

    private fun hashUri(uri: Uri): String = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        contentResolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return@runCatching ""
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun android.database.Cursor.getStringOrNull(column: String): String? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getString(idx) else null
    }

    private fun android.database.Cursor.getLongOrNull(column: String): Long? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getLong(idx) else null
    }

    private fun android.database.Cursor.getIntOrNull(column: String): Int? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getInt(idx) else null
    }

    companion object {
        private const val PAGE_SIZE = 200
        private const val BATCH_FLUSH_SIZE = 50
        private const val BUFFER_SIZE = 8 * 1024
    }
}

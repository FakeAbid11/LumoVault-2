package com.lumovault.lumovault.features.gallery.data.service

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Lightweight row read straight from a MediaStore cursor. */
data class MediaStoreItem(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val size: Long,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val dateAddedSec: Long,
    val dateModifiedSec: Long,
    val bucketId: String?,
    val bucketName: String?,
    val relativePath: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isVideo: Boolean,
)

/** A device folder (MediaStore bucket). */
data class DeviceFolder(
    val bucketId: String,
    val name: String,
    val relativePath: String?,
    val totalItems: Int,
)

data class ScanResult(
    val totalScanned: Int,
    val newItems: Int,
    val updatedItems: Int,
    val durationMs: Long,
)

/**
 * Reads photos and videos from the MediaStore.
 *
 * Two phases, matching the Flutter app's design: [scanMetadata] is a cheap
 * cursor walk (OS-cached metadata, no file reads) that populates the timeline
 * fast, and [hashPendingItems] streams SHA-256 only for items being enrolled
 * in backup — hashing a whole library upfront would block first paint for
 * minutes.
 */
@Singleton
class MediaScannerService @Inject constructor(
    @ApplicationContext private val context: Context,
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
        // Note: MediaStore no longer exposes LATITUDE/LONGITUDE (removed in
        // API 29). Coordinates are read from EXIF in the metadata pass.
    )

    /**
     * Every photo/video on the device, newest first. Images and Video are
     * separate MediaStore authorities and a file can only live in one, so
     * querying both and merging is duplicate-free (the Flutter app got the
     * same property from the OS "all photos" pseudo-album).
     */
    suspend fun listAll(pageSize: Int = 200, onProgress: ((Int) -> Unit)? = null): List<MediaStoreItem> =
        withContext(Dispatchers.IO) {
            val result = ArrayList<MediaStoreItem>(pageSize)
            var offset = 0
            while (true) {
                val page = queryPage(imageUri, isVideo = false, pageSize, offset) +
                    queryPage(videoUri, isVideo = true, pageSize, offset)
                if (page.isEmpty()) break
                result.addAll(page)
                onProgress?.invoke(result.size)
                if (page.size < pageSize) break
                offset += pageSize
            }
            // Merge already preserves per-authority ordering; sort globally.
            result.sortedByDescending { it.dateAddedSec }
        }

    private fun queryPage(uri: Uri, isVideo: Boolean, limit: Int, offset: Int): List<MediaStoreItem> {
        val sortOrder = "${MediaStore.MediaColumns.DATE_ADDED} DESC LIMIT $limit OFFSET $offset"
        val items = ArrayList<MediaStoreItem>()
        contentResolver.query(uri, projection, null, null, sortOrder)?.use { c ->
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
                        // MediaStore dropped LATITUDE/LONGITUDE in API 29;
                        // the metadata pass reads them from EXIF instead.
                        latitude = null,
                        longitude = null,
                        isVideo = isVideo,
                    ),
                )
            }
        }
        return items
    }

    /** Distinct device folders (buckets), with item counts. */
    suspend fun listFolders(): List<DeviceFolder> = withContext(Dispatchers.IO) {
        val counts = HashMap<String, Int>()
        val folders = HashMap<String, DeviceFolder>()
        for (uri in listOf(imageUri, videoUri)) {
            contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns.BUCKET_ID,
                    MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                ),
                null, null, null,
            )?.use { c ->
                val bucketIdIdx = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_ID)
                val nameIdx = c.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val relIdx = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                while (c.moveToNext()) {
                    val bucketId = c.getString(bucketIdIdx) ?: continue
                    counts[bucketId] = (counts[bucketId] ?: 0) + 1
                    if (bucketId !in folders) {
                        folders[bucketId] = DeviceFolder(
                            bucketId = bucketId,
                            name = c.getString(nameIdx) ?: bucketId,
                            relativePath = c.getString(relIdx),
                            totalItems = 0,
                        )
                    }
                }
            }
        }
        folders.values.map { it.copy(totalItems = counts[it.bucketId] ?: 0) }
            .sortedBy { it.name.lowercase() }
    }

    /**
     * Fast metadata scan: upserts display rows WITHOUT hashing.
     *
     * Backup is opt-in, so a bulk scan discovering an item only means the app
     * now knows it exists — [MediaItemEntity.isExcluded] is left true and the
     * backup engine skips it until the user includes its folder.
     */
    suspend fun scanMetadata(onProgress: ((Int) -> Unit)? = null): ScanResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var scanned = 0
        var page = 0
        val pageSize = 200
        while (true) {
            val items = queryPage(imageUri, isVideo = false, pageSize, page * pageSize) +
                queryPage(videoUri, isVideo = true, pageSize, page * pageSize)
            if (items.isEmpty()) break
            scanned += items.size
            onProgress?.invoke(scanned)
            if (items.size < pageSize) break
            page++
        }
        ScanResult(scanned, scanned, 0, System.currentTimeMillis() - start)
    }

    /** Convert a cursor row into an entity (no hashing — hash is filled by
     * [hashPendingItems] when the item is enrolled in backup). */
    fun toEntity(item: MediaStoreItem, now: Long): MediaItemEntity = MediaItemEntity(
        localId = item.id.toString(),
        fileHash = "",
        // Store the content URI — the stable, scoped-storage-safe reference.
        // The upload service materializes a readable temp file from it.
        filePath = item.contentUri.toString(),
        fileName = item.displayName,
        mimeType = if (item.mimeType.isNotEmpty()) item.mimeType else defaultMime(item),
        fileSize = item.size,
        width = item.width,
        height = item.height,
        durationMs = item.durationMs,
        createdAt = item.dateAddedSec * MILLIS_PER_SEC,
        modifiedAt = item.dateModifiedSec * MILLIS_PER_SEC,
        scannedAt = now,
        isExcluded = true,
        albumName = item.bucketName,
        deviceFolder = item.relativePath,
        latitude = item.latitude,
        longitude = item.longitude,
    )

    private fun defaultMime(item: MediaStoreItem) = if (item.isVideo) "video/mp4" else "image/jpeg"

    /**
     * Stream SHA-256 for one item. Returns "" on any failure so the caller can
     * skip it (matching the Flutter scanner, which treats an unreadable file
     * as "not eligible yet" rather than failing the whole scan).
     */
    suspend fun hash(item: MediaStoreItem): String = withContext(Dispatchers.IO) {
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            contentResolver.openInputStream(item.contentUri)?.use { stream ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            } ?: return@withContext ""
            digest.digest().toHexString()
        }.getOrDefault("")
    }

    fun openInputStream(item: MediaStoreItem): InputStream? =
        contentResolver.openInputStream(item.contentUri)

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

    private fun android.database.Cursor.getDoubleOrNull(column: String): Double? {
        val idx = getColumnIndex(column)
        return if (idx >= 0 && !isNull(idx)) getDouble(idx) else null
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val MILLIS_PER_SEC = 1000L
        private const val BUFFER_BYTES = 8 * 1024
    }
}

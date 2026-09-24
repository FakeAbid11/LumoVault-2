package com.lumovault.app.data.local.mediastore

import android.content.ContentUris
import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.Files.FileColumns
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.domain.model.mediaTypeOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads the device's media index straight from MediaStore.
 *
 * Android 10 unified images and videos into one `Files` collection, so a single filtered query
 * returns both instead of two cursors that have to be merged and re-sorted in memory. The
 * projection asks only for the columns the index stores, and nothing here opens a media file:
 * scanning tens of thousands of items must not touch their bytes.
 *
 * minSdk is 29, so `RELATIVE_PATH`, `IS_PENDING` and the `Files` collection need no version guard —
 * which is also why this file has no legacy `MediaColumns.DATA` path.
 */
class MediaStoreDataSource(private val resolver: ContentResolver) {

    /**
     * Returns the current index as rows tagged with [scanId]. The repository upserts these and then
     * deletes anything the scan did not touch, which is how removals are detected without
     * rebuilding the table.
     */
    suspend fun scan(scanId: Long): List<MediaEntity> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

        resolver.query(collection, PROJECTION, SELECTION, SELECTION_ARGS, SORT_ORDER)
            ?.use { cursor ->
                if (cursor.count <= 0) return@use emptyList()
                buildList(capacity = cursor.count.coerceAtMost(MAX_INITIAL_CAPACITY)) {
                    while (cursor.moveToNext()) {
                        cursor.toMediaEntity(collection, scanId)?.let { add(it) }
                    }
                }
            }
            ?: emptyList()
    }

    /**
     * Null for a row that cannot be indexed. One unreadable row must not abort a scan of the whole
     * library, and rows MediaStore reports as pending are mid-write.
     */
    private fun Cursor.toMediaEntity(collection: Uri, scanId: Long): MediaEntity? {
        val storeType = getInt(requireIndex(MEDIA_TYPE))
        val isVideo = storeType == FileColumns.MEDIA_TYPE_VIDEO
        if (storeType != FileColumns.MEDIA_TYPE_IMAGE && !isVideo) return null

        val id = getLong(requireIndex(ID))
        val mimeType = optionalString(MIME_TYPE)

        return MediaEntity(
            mediaStoreId = id,
            contentUri = ContentUris.withAppendedId(collection, id).toString(),
            mediaType = mediaTypeOf(mimeType, isVideo).storageKey,
            mimeType = mimeType ?: UNKNOWN_MIME_TYPE,
            displayName = optionalString(DISPLAY_NAME) ?: id.toString(),
            relativePath = optionalString(RELATIVE_PATH).orEmpty(),
            sizeBytes = getLong(requireIndex(SIZE)),
            dateAddedSeconds = getLong(requireIndex(DATE_ADDED)),
            dateModifiedSeconds = getLong(requireIndex(DATE_MODIFIED)),
            // Width and height are nullable in the Files collection; 0 keeps "unknown" out of the
            // aspect-ratio maths without inventing a sentinel.
            width = optionalInt(WIDTH) ?: 0,
            height = optionalInt(HEIGHT) ?: 0,
            durationMillis = if (isVideo) optionalLong(DURATION) else null,
            lastSeenScanId = scanId,
        )
    }

    /** Required by this projection; a missing column means the query itself is wrong. */
    private fun Cursor.requireIndex(column: String): Int = getColumnIndexOrThrow(column)

    /** Optional: `width`, `height`, `duration` and `mime_type` are genuinely nullable. */
    private fun Cursor.indexOf(column: String): Int = getColumnIndex(column)

    private fun Cursor.optionalString(column: String): String? {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.optionalInt(column: String): Int? {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) null else getInt(index)
    }

    private fun Cursor.optionalLong(column: String): Long? {
        val index = indexOf(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private companion object {
        const val ID = FileColumns._ID
        const val DISPLAY_NAME = FileColumns.DISPLAY_NAME
        const val MIME_TYPE = FileColumns.MIME_TYPE
        const val MEDIA_TYPE = FileColumns.MEDIA_TYPE
        const val SIZE = FileColumns.SIZE
        const val DATE_ADDED = FileColumns.DATE_ADDED
        const val DATE_MODIFIED = FileColumns.DATE_MODIFIED
        const val WIDTH = FileColumns.WIDTH
        const val HEIGHT = FileColumns.HEIGHT
        const val DURATION = FileColumns.DURATION
        const val RELATIVE_PATH = FileColumns.RELATIVE_PATH
        const val IS_PENDING = FileColumns.IS_PENDING

        val PROJECTION = arrayOf(
            ID,
            DISPLAY_NAME,
            MIME_TYPE,
            MEDIA_TYPE,
            SIZE,
            DATE_ADDED,
            DATE_MODIFIED,
            WIDTH,
            HEIGHT,
            DURATION,
            RELATIVE_PATH,
        )

        /** Pending rows are mid-write; they arrive on the next scan instead. */
        const val SELECTION =
            "$MEDIA_TYPE IN (?, ?) AND $IS_PENDING = 0"

        val SELECTION_ARGS = arrayOf(
            FileColumns.MEDIA_TYPE_IMAGE.toString(),
            FileColumns.MEDIA_TYPE_VIDEO.toString(),
        )

        const val SORT_ORDER = "$DATE_ADDED DESC"
        const val UNKNOWN_MIME_TYPE = "application/octet-stream"

        /** Bounds the first allocation only; the list still grows with the library. */
        const val MAX_INITIAL_CAPACITY = 4096
    }
}

package com.lumovault.lumovault.features.gallery.data.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Saves photos and videos to the device gallery with full metadata.
 *
 * Ported from Flutter `lib/features/gallery/data/repositories/gallery_save_service.dart`.
 *
 * Uses [MediaStore] to create new assets in the gallery, preserving
 * creation timestamps, GPS coordinates, and file data. On API 29+
 * uses [MediaStore.Video.Media] / [MediaStore.Images.Media] ContentResolver
 * inserts; on older APIs falls back to direct file writes to
 * [Environment.getExternalStoragePublicDirectory].
 */
@Singleton
class GallerySaveService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val contentResolver get() = context.contentResolver

    /**
     * Save an image file to the device gallery.
     *
     * @param file The source image file.
     * @param createdAt Optional creation timestamp (epoch millis).
     * @param latitude Optional GPS latitude.
     * @param longitude Optional GPS longitude.
     * @return The saved MediaStore content URI on success, or null on failure.
     */
    suspend fun saveImage(
        file: File,
        createdAt: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val filename = "lumo_${System.currentTimeMillis()}${extension(file.path)}"
            saveToGallery(file, filename, MediaStore.Images.Media.CONTENT_TYPE, createdAt, latitude, longitude)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Save a video file to the device gallery.
     *
     * @param file The source video file.
     * @param createdAt Optional creation timestamp (epoch millis).
     * @param latitude Optional GPS latitude.
     * @param longitude Optional GPS longitude.
     * @return The saved MediaStore content URI on success, or null on failure.
     */
    suspend fun saveVideo(
        file: File,
        createdAt: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val filename = "lumo_${System.currentTimeMillis()}${extension(file.path)}"
            saveToGallery(file, filename, MediaStore.Video.Media.CONTENT_TYPE, createdAt, latitude, longitude)
        } catch (e: Exception) {
            null
        }
    }

    private fun saveToGallery(
        file: File,
        filename: String,
        mimeType: String,
        createdAt: Long?,
        latitude: Double?,
        longitude: Double?,
    ): Uri? {
        val collection = if (mimeType.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/LumoVault")
            if (createdAt != null) {
                put(MediaStore.MediaColumns.DATE_ADDED, createdAt / 1000)
                put(MediaStore.MediaColumns.DATE_MODIFIED, createdAt / 1000)
            }
            if (latitude != null && longitude != null) {
                put(MediaStore.MediaColumns.LATITUDE, latitude)
                put(MediaStore.MediaColumns.LONGITUDE, longitude)
            }
        }

        val uri = contentResolver.insert(collection, values) ?: return null

        contentResolver.openOutputStream(uri)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        } ?: run {
            contentResolver.delete(uri, null, null)
            return null
        }

        return uri
    }

    private fun extension(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot >= 0) path.substring(dot) else ".jpg"
    }
}

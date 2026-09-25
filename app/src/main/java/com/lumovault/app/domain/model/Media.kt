package com.lumovault.app.domain.model

/**
 * One indexed item on the device. Metadata only: no pixels are held here, and the file itself is
 * never copied into app storage — [contentUri] is the handle every later operation (thumbnail,
 * viewer, hashing, backup) reads through.
 */
data class Media(
    /**
     * MediaStore's own row id. Stable for the life of the file and unique across images and
     * videos, because both come from the same `Files` collection.
     *
     * Deliberately not the display name: filenames collide (`IMG_0001.jpg` in two folders is
     * ordinary), so a name cannot identify a row.
     */
    val id: Long,
    val contentUri: String,
    val type: MediaType,
    val mimeType: String,
    val displayName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val dateAddedSeconds: Long,
    val dateModifiedSeconds: Long,
    val width: Int,
    val height: Int,
    /** Milliseconds; null for still images, where MediaStore reports no duration. */
    val durationMillis: Long?,
    /**
     * When the file was captured, in seconds, or null when nothing recorded it.
     *
     * MediaStore's own `DATE_TAKEN`, which it parses out of the file's EXIF or container metadata — so
     * this is the capture time the platform is willing to assert, and the viewer shows it as such. Null
     * is drawn as no date rather than as [dateAddedSeconds]: the day LumoVault first saw a file is not the
     * day it was taken, and PRD section 28 forbids presenting the two as the same fact. The timeline keeps
     * grouping by [dateAddedSeconds] unchanged, which is a different question — when this appeared in your
     * library.
     */
    val dateTakenSeconds: Long? = null,
) {
    /** Bucket used by backup-source selection and the folder picker. */
    val folder: String
        get() = relativePath.trim('/', '\\').ifBlank { "/" }

    val hasDimensions: Boolean
        get() = width > 0 && height > 0
}

/**
 * Stored as [storageKey] rather than [Enum.name] or an ordinal, so renaming or reordering an entry
 * cannot silently reinterpret rows already on a device.
 */
enum class MediaType(val storageKey: String) {
    Photo("photo"),
    Video("video"),
    Gif("gif");

    companion object {
        fun fromStorageKey(key: String?): MediaType =
            entries.firstOrNull { it.storageKey == key } ?: Photo
    }
}

/**
 * Classifies a MediaStore row.
 *
 * GIF is a first-class type in this product, and MediaStore reports `image/gif` as an ordinary
 * image row — so the MIME type has to be consulted before the row is labelled a photo, or every
 * GIF in the library would be shown as static and get no badge.
 */
fun mediaTypeOf(mimeType: String?, isVideo: Boolean): MediaType = when {
    isVideo -> MediaType.Video
    mimeType?.trim()?.lowercase() == GIF_MIME_TYPE -> MediaType.Gif
    else -> MediaType.Photo
}

private const val GIF_MIME_TYPE = "image/gif"

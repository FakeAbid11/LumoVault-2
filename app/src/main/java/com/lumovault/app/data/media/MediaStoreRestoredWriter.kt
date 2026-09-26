package com.lumovault.app.data.media

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RestorableSource
import com.lumovault.app.domain.restore.RestoreFailure
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoredMediaWriter
import com.lumovault.app.domain.restore.StoredMedia
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Writes a downloaded original into MediaStore as a pending row, then commits it.
 *
 * The pending step is the whole point. `IS_PENDING = 1` makes the row visible to *this* app and invisible
 * to the gallery, to other scanners and to every other reader until the bytes are finished — so a transfer
 * that dies three quarters of the way through a video cannot leave a truncated file in the user's camera
 * roll, which is the failure a restore is most likely to produce and the one they would never forgive.
 * Every path that gives up after the insert deletes the row it made.
 *
 * No permission is declared for this and none is missing: on API 29+ an app may insert its own media into
 * the provider without `WRITE_EXTERNAL_STORAGE`, and what it creates it owns. Reading somebody else's
 * files is the part that needs a grant, and that is Phase 3's story.
 *
 * Streams a buffer at a time and checks cancellation between buffers, exactly like [MediaFileStager],
 * because the same arithmetic applies to the same kind of file: a phone video is hundreds of megabytes,
 * and this app targets the devices that cannot hold one in memory.
 */
class MediaStoreRestoredWriter(
    private val resolver: ContentResolver,
    /** Where TDLib puts what it downloads: the volume the incoming file occupies. */
    private val downloadDirectory: File,
    /** A path on the volume the finished file is about to occupy. */
    private val mediaVolume: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : RestoredMediaWriter {

    /**
     * Both volumes have to hold it. On most phones they are the same physical storage, which makes this one
     * check; where they are not, the copy that fills the wrong one is exactly the failure being planned for.
     */
    override fun hasRoomFor(sizeBytes: Long): Boolean =
        sizeBytes <= 0L ||
            (
                downloadDirectory.usableSpace - sizeBytes >= RESERVE_BYTES &&
                    mediaVolume.usableSpace - sizeBytes >= RESERVE_BYTES
                )

    override suspend fun store(source: RestorableSource): StoredMedia = withContext(dispatcher) {
        val origin = File(source.path)
        if (!origin.isFile || origin.length() <= 0L) {
            return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.Incomplete))
        }
        val expectedBytes = origin.length()

        // The bytes decide what the file is, not the name Telegram remembers. An animation sent as a GIF
        // can come back as an MP4, and filing it under the container it *was* gives the user a gallery
        // entry no app will open. When the header announces nothing, the kind the cloud index called this
        // is the fallback: it is a guess, but it is a guess about the collection, and a video mis-filed as
        // an image is a still frame with no player.
        val kind = sniffKind(origin)
        val filing = kind ?: fallbackFor(source.mediaType)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, nameFor(source, origin.name, kind))
            put(
                MediaStore.MediaColumns.MIME_TYPE,
                kind?.mimeType ?: source.declaredMimeType.takeIf { it.isNotBlank() } ?: filing.mimeType,
            )
            put(MediaStore.MediaColumns.RELATIVE_PATH, filing.relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val row = try {
            resolver.insert(collectionFor(filing), values)
        } catch (refused: Exception) {
            // Broad because the provider's refusals are several types — SecurityException when the volume
            // is not ours to write, IllegalArgumentException for a values set it rejects — and all of them
            // mean the same thing to a restore: this file could not be filed.
            null
        } ?: return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.SaveRejected))

        val written = try {
            copyInto(origin, row)
        } catch (missing: FileNotFoundException) {
            abandon(row)
            return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.Incomplete))
        } catch (failed: IOException) {
            abandon(row)
            return@withContext StoredMedia.Refused(
                RestoreFailure(
                    if (mediaVolume.usableSpace < MIN_REMAINING_BYTES) {
                        RestoreFailureKind.InsufficientSpace
                    } else {
                        RestoreFailureKind.Incomplete
                    },
                ),
            )
        } catch (cancelled: CancellationException) {
            abandon(row)
            throw cancelled
        }

        if (written != expectedBytes) {
            // A short write is not a saved file, and MediaStore would show it as one.
            abandon(row)
            return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.Incomplete))
        }

        val commit = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val published = try {
            resolver.update(row, commit, null, null)
        } catch (refused: Exception) {
            0
        }
        if (published != 1) {
            abandon(row)
            return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.SaveRejected))
        }

        val mediaStoreId = row.lastPathSegment?.toLongOrNull()
        if (mediaStoreId == null || mediaStoreId <= 0L) {
            // The file is whole and the row exists, but it cannot be traced back to an id — and a restore
            // that cannot be associated is one that will be uploaded again. Reported as a refusal, with the
            // file left where it is: deleting the user's new photo to keep a database tidy is not a trade.
            return@withContext StoredMedia.Refused(RestoreFailure(RestoreFailureKind.SaveRejected))
        }

        StoredMedia.Ready(contentUri = row.toString(), mediaStoreId = mediaStoreId, sizeBytes = written)
    }

    /** The container the bytes announce, or null when nothing here recognises them. */
    private fun sniffKind(origin: File): FileKind? = try {
        FileInputStream(origin).use { FileKindSniffing.sniff(it) }
    } catch (unreadable: IOException) {
        null
    }

    private suspend fun copyInto(origin: File, row: Uri): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var written = 0L

        FileInputStream(origin).use { source ->
            val sink = resolver.openOutputStream(row) ?: throw FileNotFoundException("no stream for $row")
            sink.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    it.write(buffer, 0, read)
                    written += read
                }
                it.flush()
            }
        }
        return written
    }

    private fun abandon(row: Uri) {
        // Deleting a pending row needs no consent prompt because this app created it and no other reader
        // has ever been allowed to see it. The consent path — `MediaStore.createDeleteRequest`, which
        // Phase 7 uses for files the user can see — would be absurd here, and Free Up Space is where that
        // one belongs.
        runCatching { resolver.delete(row, null, null) }
    }

    private fun collectionFor(kind: FileKind): Uri = when (kind.mediaType) {
        MediaType.Photo -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaType.Video, MediaType.Gif -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }

    /**
     * The display name, with an extension that matches the bytes.
     *
     * MediaStore shows this name in the system UI, so `IMG_2049.jpg` on an MP4 file is a small lie the user
     * would have to debug. When the header said nothing, the name Telegram carries is kept as it is.
     */
    private fun nameFor(source: RestorableSource, fallbackOnDisk: String, kind: FileKind?): String {
        val base = (source.preferredName.takeIf { it.isNotBlank() } ?: fallbackOnDisk)
            .substringBeforeLast('.')
            .ifBlank { "restored" }
        val extension = kind?.extension ?: source.preferredName.substringAfterLast('.', "")
        return if (extension.isBlank()) base else "$base.$extension"
    }

    /**
     * The kind to assume when the header said nothing.
     *
     * A guess, and one that decides only the collection and the MIME type: it follows the type the cloud
     * index recorded for this message. A mis-filed video is a still frame with no player and a mis-filed
     * photo is still viewable, which is why the fallback is never the other way round. The alternative —
     * refusing a restore whose bytes are otherwise perfectly good — is the worse mistake.
     */
    private fun fallbackFor(mediaType: MediaType): FileKind = when (mediaType) {
        MediaType.Photo -> FileKind.Jpeg
        MediaType.Video, MediaType.Gif -> FileKind.Mp4
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** Room left over after the file, so the write is never the thing that fills the volume. */
        const val RESERVE_BYTES = 64L * 1024 * 1024

        /** Below this, a failed write is reported as a full volume rather than a bad download. */
        const val MIN_REMAINING_BYTES = 8L * 1024 * 1024
    }
}

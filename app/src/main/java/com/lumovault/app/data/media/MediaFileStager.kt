package com.lumovault.app.data.media

import android.content.ContentResolver
import android.net.Uri
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.MediaSourceStager
import com.lumovault.app.domain.backup.StagedSource
import java.io.File
import java.io.FileOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Copies MediaStore content into LumoVault's own directory so TDLib can be given a path.
 *
 * This is the first code in the app that reads a media file's bytes — scanning deliberately never
 * does — so it is written to the constraints that reading imposes. The copy is streamed a buffer at
 * a time: a phone video is routinely hundreds of megabytes, and the low-memory devices this project
 * targets are exactly where `readAllBytes()` would fail. Nothing here holds a file open across a
 * suspension point, and cancellation is checked every buffer, because a queue the user can cancel is
 * only honest if cancelling actually stops the copying.
 *
 * The size is taken from the bytes counted while copying rather than from MediaStore. A row in the
 * index can be a moment out of date, and TDLib reports progress against the file it is holding — the
 * two have to agree, and the file is the one that is about to be sent.
 */
class MediaFileStager(
    private val resolver: ContentResolver,
    private val directory: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Refuse a copy that would leave too little space for TDLib to do its own work. */
    private val reserveBytes: Long = RESERVE_BYTES,
) : MediaSourceStager {

    override fun usableSpaceBytes(): Long = directory.usableSpace

    override suspend fun stage(contentUri: String, displayName: String): StagedSource =
        withContext(dispatcher) {
            val size = expectedSize(contentUri)
            if (size == null) {
                return@withContext StagedSource.Unavailable(
                    BackupFailure(BackupFailureKind.SourceMissing),
                )
            }
            if (size > 0L && directory.usableSpace - size < reserveBytes) {
                return@withContext StagedSource.Unavailable(
                    BackupFailure(BackupFailureKind.InsufficientSpace),
                )
            }

            val target = File(directory, fileNameFor(contentUri, displayName))
            val copied = try {
                copy(contentUri, target)
            } catch (missing: FileNotFoundException) {
                target.delete()
                return@withContext StagedSource.Unavailable(
                    BackupFailure(BackupFailureKind.SourceMissing),
                )
            } catch (failed: IOException) {
                target.delete()
                return@withContext StagedSource.Unavailable(
                    BackupFailure(failureFor(failed, target.parentFile)),
                )
            } catch (cancelled: CancellationException) {
                // A cancelled copy must not leave a half-written file that a later attempt would
                // upload as though it were the original.
                target.delete()
                throw cancelled
            }

            if (copied == 0L) {
                target.delete()
                return@withContext StagedSource.Unavailable(
                    BackupFailure(BackupFailureKind.SourceUnreadable),
                )
            }

            StagedSource.Ready(target.absolutePath, copied)
        }

    override fun discard(path: String) {
        if (path.isNotBlank()) File(path).delete()
    }

    override fun purgeStale() {
        // A staged file is always a copy, so anything found here after a fresh start belongs to
        // nothing. Deleting it returns the disk; keeping it would only make the next refusal
        // ("not enough space") more likely.
        directory.listFiles()?.forEach { it.delete() }
    }

    /** Returns the bytes written, or throws if the source cannot be opened or read. */
    private suspend fun copy(contentUri: String, target: File): Long {
        val bytes = ByteArray(BUFFER_BYTES)
        var written = 0L

        resolver.openInputStream(Uri.parse(contentUri)).use { source ->
            if (source == null) throw FileNotFoundException("no stream for $contentUri")
            FileOutputStream(target).use { sink ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(bytes)
                    if (read < 0) break
                    sink.write(bytes, 0, read)
                    written += read
                }
                sink.fd.sync()
            }
        }

        return written
    }

    /**
     * MediaStore's own size for the item, read through the URI rather than the index row.
     *
     * Three answers, not two. `null` means the document cannot be opened at all — deleted, or no
     * longer covered by the grant. `0` means the provider declines to state a length, which is legal
     * and must not be read as "empty file": the copy will count the bytes and the space check is
     * skipped. An actual length is used to refuse a copy that cannot fit before it starts, rather
     * than three quarters of the way through a video.
     */
    private fun expectedSize(contentUri: String): Long? = try {
        resolver.openAssetFileDescriptor(Uri.parse(contentUri), "r").use { descriptor ->
            when {
                descriptor == null -> null
                descriptor.length < 0L -> 0L
                else -> descriptor.length
            }
        }
    } catch (unreadable: IOException) {
        null
    }

    /**
     * An I/O failure part-way through writing is nearly always the disk filling, and telling the
     * user their file is unreadable when the real problem is space would send them to the wrong fix.
     * Checked against the directory's own free space because that is the quantity that ran out.
     */
    private fun failureFor(error: IOException, directory: File?): BackupFailureKind {
        val outOfRoom = directory != null && directory.usableSpace < MIN_REMAINING_BYTES
        return if (outOfRoom) BackupFailureKind.InsufficientSpace else BackupFailureKind.SourceUnreadable
    }

    private fun fileNameFor(contentUri: String, displayName: String): String {
        // The URI's last path segment is MediaStore's row id, so two items never collide here even
        // when their display names do — which they constantly do across folders and camera rolls.
        val id = Uri.parse(contentUri).lastPathSegment?.filter { it.isDigit() }.orEmpty()
        val suffix = displayName.substringAfterLast('.', "").take(8).lowercase()
        return if (suffix.isEmpty()) "media_$id" else "media_$id.$suffix"
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /**
         * TDLib writes its own state and a partial upload alongside the file we hand it, so the free
         * space we insist on is the copy plus room for that. Two hundred megabytes is well under any
         * plausible video and well over the database's own appetite.
         */
        const val RESERVE_BYTES = 200L * 1024 * 1024

        /** Below this, a write that failed is treated as the volume being full rather than the
         * source being unreadable. */
        const val MIN_REMAINING_BYTES = 8L * 1024 * 1024
    }
}

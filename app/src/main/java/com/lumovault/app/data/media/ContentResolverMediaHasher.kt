package com.lumovault.app.data.media

import android.content.ContentResolver
import android.net.Uri
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.ContentDigest
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.backup.digestStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hashes MediaStore content in place.
 *
 * No copy is made first: [com.lumovault.app.data.backup.BackupUploadWorker] may stage a hundred
 * megabytes to hand TDLib a path, but recognition asks a different question — *what is this file* — and
 * answering it from the provider's own stream costs the user no disk and leaves nothing behind to
 * clean up.
 *
 * The hash is of the original bytes the resolver hands out, which is the only version of the file
 * LumoVault is allowed to claim an identity for. A thumbnail or a decoded bitmap would produce a
 * different digest for the same photo depending on who rendered it, and PRD section 11 needs one answer
 * per file.
 *
 * Runs on [Dispatchers.IO] because a hash of a large video is a disk-bound read of every byte of it: on
 * the main thread it would freeze the gallery, which phase 6 section 3 forbids outright.
 */
class ContentResolverMediaHasher(
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaContentHasher {

    override suspend fun hash(contentUri: String): ContentDigest = withContext(dispatcher) {
        try {
            resolver.openInputStream(Uri.parse(contentUri)).use { stream -> digestStream(stream) }
        } catch (missing: FileNotFoundException) {
            ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceMissing))
        } catch (unreadable: IOException) {
            // The URI resolved and then the read failed — permission revoked mid-file, or the provider
            // dropping the descriptor. Either way the bytes are not all here, so no identity is earned.
            ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
        }
    }

    override suspend fun hashFile(path: String): ContentDigest = withContext(dispatcher) {
        if (path.isBlank()) {
            return@withContext ContentDigest.Unavailable(
                BackupFailure(BackupFailureKind.SourceMissing),
            )
        }
        try {
            File(path).inputStream().use { stream -> digestStream(stream) }
        } catch (missing: FileNotFoundException) {
            // A staged copy that vanished was evicted from cache or never written; either way the bytes are
            // not here, so nothing may be hashed — and nothing claimed about what they were.
            ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceMissing))
        } catch (unreadable: IOException) {
            ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
        }
    }
}

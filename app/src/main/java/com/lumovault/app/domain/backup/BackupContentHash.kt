package com.lumovault.app.domain.backup

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The SHA-256 of one media file's own bytes, and how many bytes were read to produce it.
 *
 * [Computed] carries [Computed.sizeBytes] because the count comes from the same pass over the stream.
 * A hash and a size measured at two different times can disagree about a file that is being written,
 * and the layered recognition in Phase 6 depends on the pair describing one moment.
 *
 * [Unavailable] is a whole case rather than a null return because what went wrong decides what happens
 * next: a file MediaStore no longer has is a permanent fact about the item, and a stream that threw
 * halfway through is not. Both must leave the item *not* backed up, which is the one thing a failed
 * hash must never be allowed to imply.
 */
sealed interface ContentDigest {
    data class Computed(val hash: String, val sizeBytes: Long) : ContentDigest
    data class Unavailable(val failure: BackupFailure) : ContentDigest
}

/**
 * Hashes a stream a buffer at a time.
 *
 * This is the exact-content identifier PRD section 11 asks for, so the constraints on it are strict:
 * it reads the bytes it is handed and nothing else — never a thumbnail, never decoded pixels, never a
 * resized copy — because a hash of a derived image would identify the derivation rather than the file.
 *
 * It never holds the file. `readAllBytes()` on a phone video would put hundreds of megabytes in memory
 * on the devices this app targets, so [MessageDigest] is fed one [HASH_BUFFER_BYTES] buffer at a time.
 * Cancellation is checked per buffer, which is what makes a cancelled pass stop inside a fraction of a
 * megabyte rather than after a whole film.
 *
 * A null stream is [BackupFailureKind.SourceMissing] rather than an exception: a URI that no longer
 * resolves is an ordinary outcome for a library scanned while files moved, not a programming error.
 */
suspend fun digestStream(input: InputStream?): ContentDigest {
    if (input == null) {
        return ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceMissing))
    }

    val digest = try {
        MessageDigest.getInstance(SHA_256)
    } catch (absent: NoSuchAlgorithmException) {
        // Every Android runtime has SHA-256; a JVM without it is a test-environment fact, and it must
        // read as "no hash" rather than as a hash of nothing.
        return ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
    }

    val buffer = ByteArray(HASH_BUFFER_BYTES)
    var read = 0L
    return try {
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            read += count
        }
        ContentDigest.Computed(digest.digest().toHexString(), read)
    } catch (missing: FileNotFoundException) {
        ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceMissing))
    } catch (failed: IOException) {
        ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))
    }
}

/** Lowercase hex, the form SHA-256 digests are conventionally written in and what the manifest stores. */
fun ByteArray.toHexString(): String {
    // Explicit nibbles rather than `joinToString("%02x")`: the latter allocates a formatter per byte,
    // and this runs over every byte of every file the app has ever backed up.
    val out = StringBuilder(size * 2)
    for (byte in this) {
        val value = byte.toInt() and 0xFF
        out.append(HEX_DIGITS[value shr 4]).append(HEX_DIGITS[value and 0x0F])
    }
    return out.toString()
}

/** Bytes read per hashing pass. Matches the staging copy's own buffer so neither is the narrow one. */
const val HASH_BUFFER_BYTES: Int = 128 * 1024

private const val SHA_256 = "SHA-256"
private const val HEX_DIGITS = "0123456789abcdef"

/**
 * Reads MediaStore content and hashes it, without copying the file anywhere first.
 *
 * Recognition has to answer "what is this file's identity" for items that were never queued, and
 * staging exists to put bytes where TDLib can read them — a question hashing never asks. Reading
 * through the URI means hashing a file that will not be uploaded costs disk nothing and leaves no
 * copy behind.
 */
interface MediaContentHasher {
    suspend fun hash(contentUri: String): ContentDigest

    /**
     * Hashes a file already on disk, which is what the upload path does with its staged copy.
     *
     * Hashing the bytes that are about to be sent rather than a second read of MediaStore removes a race
     * that would otherwise be permanent: a file edited between copying and hashing would be sent under a
     * manifest describing what it used to be, and no later pass could tell the two apart.
     */
    suspend fun hashFile(path: String): ContentDigest
}

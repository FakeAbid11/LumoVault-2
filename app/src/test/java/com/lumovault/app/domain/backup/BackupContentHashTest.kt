package com.lumovault.app.domain.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SHA-256 over a stream, which is the identity every other Phase 6 promise rests on.
 *
 * The expected digests are the published vectors for those inputs, not this code's own output copied back
 * as an expectation: if a digest were ever computed over something other than the bytes handed in — a
 * prefix, a decoded copy, a re-encoded one — a self-referential test would still pass while every file in
 * the library was matched against an identity that means nothing.
 *
 * The rest of the class is about the one property that makes hashing a photo library possible at all: the
 * file is never held in memory. That is checked by watching what the implementation asks the stream for,
 * because "it did not allocate four gigabytes" is not observable from a digest alone.
 */
class BackupContentHashTest {

    @Test
    fun knownTextHashesToThePublishedVector() = runBlocking {
        val digest = digestStream("abc".byteInputStream())

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            (digest as ContentDigest.Computed).hash,
        )
        assertEquals(3L, digest.sizeBytes)
    }

    @Test
    fun anEmptyFileIsARealHashRatherThanAFailure() = runBlocking {
        val digest = digestStream(ByteArrayInputStream(ByteArray(0)))

        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            (digest as ContentDigest.Computed).hash,
        )
        assertEquals("zero bytes is still an answer about the file", 0L, digest.sizeBytes)
    }

    @Test
    fun binaryContentIsHashedAsBytesAndNotAsText() = runBlocking {
        // Every byte value, including those that are not valid UTF-8 and those a text reader would stop
        // at: a hash of a decoded string would differ from a hash of the file for the same photo.
        val bytes = ByteArray(256) { it.toByte() }

        val digest = digestStream(ByteArrayInputStream(bytes))

        assertEquals(
            "40aff2e9d2d8922e47afd4648e6967497158785fbd1da870e7110266bf944880",
            (digest as ContentDigest.Computed).hash,
        )
        assertEquals(256L, digest.sizeBytes)
    }

    @Test
    fun aFileLargerThanTheBufferIsStreamedAndNeverLoaded() = runBlocking {
        val file = tempFileOf(BUFFERS * HASH_BUFFER_BYTES)
        val counted = CountingStream(file.inputStream())
        try {
            val digest = digestStream(counted)

            assertTrue(
                "the largest read asked of the stream was ${counted.largestRequest} bytes",
                counted.largestRequest <= HASH_BUFFER_BYTES,
            )
            assertEquals(
                "every byte was read, exactly once",
                (BUFFERS * HASH_BUFFER_BYTES).toLong(),
                (digest as ContentDigest.Computed).sizeBytes,
            )
            assertEquals(referenceDigestOf(file), digest.hash)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aVideoShapedFileHashesTheSameWhollyInCopyAsItDoesOnDisk() = runBlocking {
        // 40 KB of an irregular pattern written 100 times. The reference implementation below is allowed
        // to load it into memory: it exists only to be disagreed with.
        val pattern = ByteArray(401) { (it * 29 + 7).toByte() }
        val file = tempFileFrom(pattern, repeats = 100)
        try {
            val digest = digestStream(file.inputStream())

            assertEquals(referenceDigestOf(file), (digest as ContentDigest.Computed).hash)
            assertEquals((pattern.size * 100).toLong(), digest.sizeBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun aStreamThatNeverOpenedIsReportedAsMissingNotAsAnEmptyHash() = runBlocking {
        val digest = digestStream(null)

        assertEquals(BackupFailureKind.SourceMissing, (digest as ContentDigest.Unavailable).failure.kind)
    }

    @Test
    fun aStreamThatFailsHalfwayThroughYieldsNoIdentity() = runBlocking {
        val source = object : ByteArrayInputStream("a photo's bytes".toByteArray()) {
            private var reads = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                reads += 1
                if (reads > 1) throw IOException("the provider dropped the descriptor mid-read")
                return super.read(buffer, offset, length)
            }
        }

        val digest = digestStream(source)

        assertEquals(BackupFailureKind.SourceUnreadable, (digest as ContentDigest.Unavailable).failure.kind)
    }

    @Test
    fun aRowThatStoppedResolvingIsStillReportedAsGone() = runBlocking {
        val missing = object : InputStream() {
            override fun read(): Int = throw FileNotFoundException("no such row")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                throw FileNotFoundException("no such row")
        }

        assertEquals(
            BackupFailureKind.SourceMissing,
            (digestStream(missing) as ContentDigest.Unavailable).failure.kind,
        )
    }

    @Test
    fun aCancelledPassStopsReadingInsteadOfFinishingTheFile() {
        val file = tempFileOf(BUFFERS * HASH_BUFFER_BYTES)
        val job = Job()
        val counted = CountingStream(file.inputStream()) { job.cancel() }
        val context: CoroutineContext = job + Dispatchers.Unconfined

        try {
            // The stream cancels its own coroutine on the third read, so this is deterministic: what is
            // being asserted is that the loop notices at the next buffer boundary rather than after
            // reading the rest of the file.
            assertThrows(CancellationException::class.java) {
                runBlocking(context) { digestStream(counted) }
            }
            assertEquals(3, counted.reads)
        } finally {
            file.delete()
        }
    }

    @Test
    fun hexIsLowercaseFixedWidthAndComplete() {
        assertEquals(64, ByteArray(32) { (it + 1).toByte() }.toHexString().length)
        assertEquals("000f10ff", byteArrayOf(0x00, 0x0F, 0x10, 0xFF.toByte()).toHexString())
    }

    /**
     * Reads a whole file into memory to hash it, which is exactly what production code must not do. It is
     * here to compare the streaming implementation against, and nowhere else.
     */
    private fun referenceDigestOf(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toHexString()

    private fun tempFileOf(sizeBytes: Int): File {
        val bytes = ByteArray(sizeBytes)
        // Not all one byte: a short read or a reused buffer has to change the digest rather than quietly
        // reproduce it.
        for (index in bytes.indices) bytes[index] = (index % 251).toByte()
        return File.createTempFile("lumovault-hash", ".bin").apply {
            outputStream().use { it.write(bytes) }
        }
    }

    private fun tempFileFrom(pattern: ByteArray, repeats: Int): File =
        File.createTempFile("lumovault-hash", ".bin").apply {
            outputStream().use { sink -> repeat(repeats) { sink.write(pattern) } }
        }

    /** Counts what the hasher asks for, and can cancel the pass that is reading it. */
    private class CountingStream(
        source: InputStream,
        private val onThirdRead: () -> Unit = {},
    ) : java.io.BufferedInputStream(source) {
        var largestRequest = 0
        var reads = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            largestRequest = maxOf(largestRequest, length)
            reads += 1
            if (reads == 3) onThirdRead()
            return super.read(buffer, offset, length)
        }
    }

    private companion object {
        /** Enough buffers that the hashing loop runs more than once. */
        const val BUFFERS = 6
    }
}

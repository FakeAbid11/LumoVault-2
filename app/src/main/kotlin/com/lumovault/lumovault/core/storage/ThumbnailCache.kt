package com.lumovault.lumovault.core.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.LinkedHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Two-tier thumbnail cache: in-memory LRU + on-disk FIFO.
 *
 * Ported from Flutter `lib/core/storage/thumbnail_cache.dart`.
 *
 * Memory cache is a true LRU via [LinkedHashMap] with access-ordering,
 * capped at [MAX_MEMORY_ENTRIES].
 *
 * Disk cache lives at `<cacheDir>/thumbnails/` and is evicted by file
 * modification time (oldest first) when total size exceeds
 * [MAX_DISK_SIZE_BYTES]. Reads never touch the file, so mtime acts as
 * a FIFO insertion order.
 *
 * Eviction runs every [EVICT_INTERVAL] calls to [put] to amortize
 * the cost, and a re-entrancy guard prevents concurrent sweeps.
 */
@Singleton
class ThumbnailCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cacheDir: File by lazy {
        File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    }

    /** True LRU: access-order reorders on get. */
    private val memoryCache = object : LinkedHashMap<String, ByteArray>(
        INITIAL_CAPACITY, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ByteArray>?
        ): Boolean = size > MAX_MEMORY_ENTRIES
    }

    private val mutex = Mutex()
    private var putCount = 0
    @Volatile
    private var evictionRunning = false

    // ------------------------------------------------------------------ reads

    /**
     * Return cached bytes for [mediaItemId], checking memory then disk.
     * Returns null on miss.
     */
    suspend fun get(mediaItemId: String): ByteArray? = mutex.withLock {
        memoryCache[mediaItemId]?.let { return it }

        val file = diskFile(mediaItemId)
        if (file.exists()) {
            val bytes = withContext(Dispatchers.IO) { file.readBytes() }
            memoryCache[mediaItemId] = bytes
            return bytes
        }

        null
    }

    /** Synchronous memory-only check for hot paths that shouldn't suspend. */
    fun containsMemory(mediaItemId: String): Boolean =
        memoryCache.containsKey(mediaItemId)

    // ------------------------------------------------------------------- writes

    /** Store [bytes] in both tiers. */
    suspend fun put(mediaItemId: String, bytes: ByteArray) = mutex.withLock {
        memoryCache[mediaItemId] = bytes

        withContext(Dispatchers.IO) {
            try {
                FileOutputStream(diskFile(mediaItemId)).use { it.write(bytes) }
            } catch (_: Exception) {
                // Best-effort disk write; memory copy is already live.
            }
        }

        putCount++
        if (putCount % EVICT_INTERVAL == 0 && !evictionRunning) {
            evictIfOverSize()
        }
    }

    // ----------------------------------------------------------------- removal

    suspend fun remove(mediaItemId: String) = mutex.withLock {
        memoryCache.remove(mediaItemId)
        withContext(Dispatchers.IO) { diskFile(mediaItemId).delete() }
    }

    suspend fun clear() = mutex.withLock {
        memoryCache.clear()
        withContext(Dispatchers.IO) {
            cacheDir.deleteRecursively()
            cacheDir.mkdirs()
        }
    }

    // ----------------------------------------------------------------- stats

    suspend fun diskCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    suspend fun diskCacheCount(): Int = withContext(Dispatchers.IO) {
        cacheDir.listFiles()?.size ?: 0
    }

    // ---------------------------------------------------------------- eviction

    /** Evict disk entries when total size exceeds [MAX_DISK_SIZE_BYTES]. */
    private suspend fun evictIfOverSize() {
        if (evictionRunning) return
        evictionRunning = true
        try {
            withContext(Dispatchers.IO) {
                val files = cacheDir.listFiles() ?: return@withContext
                var total = files.sumOf { it.length() }
                if (total <= MAX_DISK_SIZE_BYTES) return@withContext

                val sorted = files.sortedBy { it.lastModified() }
                for (f in sorted) {
                    if (total <= MAX_DISK_SIZE_BYTES) break
                    val len = f.length()
                    if (f.delete()) total -= len
                }
            }
        } finally {
            evictionRunning = false
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun diskFile(mediaItemId: String): File =
        File(cacheDir, "${mediaItemId}.jpg")

    companion object {
        private const val DIR_NAME = "thumbnails"
        private const val INITIAL_CAPACITY = 128
        private const val MAX_MEMORY_ENTRIES = 100
        const val MAX_DISK_SIZE_BYTES = 200L * 1024 * 1024
        private const val EVICT_INTERVAL = 64
    }
}

package com.lumovault.lumovault.core.storage

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Size
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-warms the [ThumbnailCache] during scans, fire-and-forget.
 *
 * Ported from Flutter `lib/core/storage/thumbnail_warmup.dart`.
 *
 * Timeline tiles consult ThumbnailCache first, so the fastest way to get
 * real thumbnails after a scan is to populate the cache while scanning
 * rather than making each tile generate its own on first paint.
 *
 * Work is capped at [MAX_CONCURRENT] concurrent MediaStore thumbnail calls.
 * The pending backlog is capped at [MAX_QUEUED]; past this, new work is
 * dropped (best-effort warmup -- a miss just means the tile regenerates
 * on demand).
 */
@Singleton
class ThumbnailWarmup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: ThumbnailCache,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val queue = ArrayList<suspend () -> Unit>()
    private var active = 0

    /**
     * Schedule a thumbnail decode for [mediaStoreId].
     * Safe to call from any thread; failures are swallowed.
     */
    fun schedule(mediaStoreId: Long) {
        if (active >= MAX_CONCURRENT && queue.size >= MAX_QUEUED) return

        val job: suspend () -> Unit = {
            try {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    mediaStoreId,
                )
                val bitmap: Bitmap? = try {
                    context.contentResolver.loadThumbnail(uri, Size(THUMB_SIZE, THUMB_SIZE), null)
                } catch (_: Exception) {
                    null
                }
                if (bitmap != null) {
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
                    cache.put(mediaStoreId.toString(), stream.toByteArray())
                    bitmap.recycle()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Best-effort: a failed warmup only costs the on-demand regenerate.
            }
        }

        synchronized(this) {
            if (active < MAX_CONCURRENT) {
                active++
                scope.launch {
                    try {
                        job()
                    } finally {
                        finish()
                    }
                }
            } else {
                queue.add(job)
            }
        }
    }

    @Synchronized
    private fun finish() {
        active--
        while (active < MAX_CONCURRENT && queue.isNotEmpty()) {
            active++
            val next = queue.removeFirst()
            scope.launch {
                try {
                    next()
                } finally {
                    finish()
                }
            }
        }
    }

    companion object {
        private const val MAX_CONCURRENT = 4
        private const val MAX_QUEUED = 512
        private const val THUMB_SIZE = 300
        private const val JPEG_QUALITY = 85
    }
}

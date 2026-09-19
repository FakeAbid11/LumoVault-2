package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import com.lumovault.lumovault.features.gallery.data.service.ClipEmbeddingService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ClipEmbeddingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: GalleryRepository,
    private val clipService: ClipEmbeddingService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val items = repository.itemsNeedingEmbedding().take(BATCH_SIZE)
            for (item in items) {
                if (!isStopped) {
                    val bytes = readItemBytes(item.filePath) ?: continue
                    val embedding = clipService.embedImage(bytes)
                    if (embedding != null) {
                        repository.updateClipEmbedding(item.localId, embedding)
                    }
                }
            }
            Result.success()
        } catch (_: Throwable) {
            Result.retry()
        }
    }

    private fun readItemBytes(path: String): ByteArray? {
        return try {
            val uri = Uri.parse(path)
            applicationContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (_: Throwable) { null }
    }

    companion object {
        const val WORK_NAME = "lumovault_clip_embedding"
        const val BATCH_SIZE = 20
    }
}

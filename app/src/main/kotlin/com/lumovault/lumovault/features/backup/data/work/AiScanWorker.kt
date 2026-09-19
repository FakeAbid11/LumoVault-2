package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.lumovault.features.gallery.data.repository.GalleryRepository
import com.lumovault.lumovault.features.gallery.data.service.ImageClassifierService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class AiScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: GalleryRepository,
    private val classifier: ImageClassifierService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val labeledIds = repository.labeledLocalIds()
            val items = repository.itemsNeedingEmbedding()
                .filter { it.localId !in labeledIds }
                .take(BATCH_SIZE)
            for (item in items) {
                if (!isStopped) {
                    val bytes = readItemBytes(item.filePath) ?: continue
                    val labels = classifier.classify(bytes)
                    if (!labels.isNullOrEmpty()) {
                        repository.labelMediaItem(item.localId, labels)
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
        const val WORK_NAME = "lumovault_ai_scan"
        const val BATCH_SIZE = 50
    }
}

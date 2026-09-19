package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.lumovault.core.database.dao.FaceDao
import com.lumovault.lumovault.core.database.entity.FaceEntity
import com.lumovault.lumovault.core.database.entity.FaceScanEntity
import com.lumovault.lumovault.features.people.data.service.FaceDetectionService
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class FaceScanWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val faceDao: FaceDao,
    private val detector: FaceDetectionService,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val scannedIds = faceDao.scannedMediaItemIds().toSet()
            val allItems = faceDao.mediaItemsForPerson(emptyList())
                .filter { it.localId !in scannedIds }
                .take(BATCH_SIZE)

            for (item in allItems) {
                if (isStopped) break
                val bytes = readItemBytes(item.filePath) ?: run {
                    faceDao.markMediaItemScanned(
                        FaceScanEntity(item.localId, System.currentTimeMillis(), 0)
                    )
                    continue
                }
                try {
                    val result = detector.detectFaces(bytes)
                    val now = System.currentTimeMillis()
                    val faces = result.faces.map { face ->
                        FaceEntity(
                            mediaItemId = item.localId,
                            boundingBoxX = face.left,
                            boundingBoxY = face.top,
                            boundingBoxWidth = face.right - face.left,
                            boundingBoxHeight = face.bottom - face.top,
                            embedding = face.embedding.map { it.toDouble() },
                            embeddingModel = detector.embedderModelTag,
                            confidence = face.confidence,
                            thumbnailPath = face.thumbnailPath,
                            createdAt = now,
                        )
                    }
                    faceDao.insertFaces(faces)
                    faceDao.markMediaItemScanned(
                        FaceScanEntity(item.localId, now, faces.size)
                    )
                } catch (_: FaceDetectionService.FaceDetectorUnavailable) {
                    faceDao.markMediaItemScanned(
                        FaceScanEntity(item.localId, System.currentTimeMillis(), 0)
                    )
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
        const val WORK_NAME = "lumovault_face_scan"
        const val BATCH_SIZE = 30
    }
}

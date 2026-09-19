package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lumovault.lumovault.features.settings.data.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiScheduleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
) {

    private val workManager = WorkManager.getInstance(context)

    fun syncSchedule() {
        val settings = settingsRepository.currentBlocking()
        if (settings.aiScanEnabled) {
            enqueuePeriodicAiScan()
        } else {
            workManager.cancelUniqueWork(AiScanWorker.WORK_NAME)
            workManager.cancelUniqueWork(ClipEmbeddingWorker.WORK_NAME)
            workManager.cancelUniqueWork(FaceScanWorker.WORK_NAME)
        }
    }

    fun startOnce() {
        workManager.enqueueUniqueWork(
            AiScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AiScanWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build(),
        )
        workManager.enqueueUniqueWork(
            ClipEmbeddingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ClipEmbeddingWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build(),
        )
        workManager.enqueueUniqueWork(
            FaceScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<FaceScanWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build())
                .build(),
        )
    }

    fun stopAll() {
        workManager.cancelUniqueWork(AiScanWorker.WORK_NAME)
        workManager.cancelUniqueWork(ClipEmbeddingWorker.WORK_NAME)
        workManager.cancelUniqueWork(FaceScanWorker.WORK_NAME)
    }

    private fun enqueuePeriodicAiScan() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(true)
            .build()

        val aiWork = PeriodicWorkRequestBuilder<AiScanWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        val clipWork = PeriodicWorkRequestBuilder<ClipEmbeddingWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        val faceWork = PeriodicWorkRequestBuilder<FaceScanWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniquePeriodicWork(
            AiScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            aiWork,
        )
        workManager.enqueueUniquePeriodicWork(
            ClipEmbeddingWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            clipWork,
        )
        workManager.enqueueUniquePeriodicWork(
            FaceScanWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            faceWork,
        )
    }
}

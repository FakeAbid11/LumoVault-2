package com.lumovault.lumovault

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class LumoVaultApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: com.lumovault.lumovault.features.settings.data.SettingsRepository

    @Inject
    lateinit var backupScheduler: com.lumovault.lumovault.features.backup.data.work.BackupScheduler

    @Inject
    lateinit var aiScheduleManager: com.lumovault.lumovault.features.backup.data.work.AiScheduleManager

    override fun onCreate() {
        super.onCreate()

        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default,
        )
        scope.launch {
            val apply: (com.lumovault.lumovault.features.settings.domain.model.AppSettings) -> Unit = { s ->
                backupScheduler.reschedule(
                    backgroundEnabled = s.backgroundBackupEnabled && s.autoBackupEnabled,
                    wifiOnly = s.wifiOnly,
                )
                aiScheduleManager.syncSchedule()
            }
            apply(settingsRepository.load())
            settingsRepository.changes.collect(apply)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}

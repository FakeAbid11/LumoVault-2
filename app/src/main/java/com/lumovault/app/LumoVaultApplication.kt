package com.lumovault.app

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import com.lumovault.app.data.backup.BackupWorkerFactory

class LumoVaultApplication : Application(), Configuration.Provider {
    lateinit var container: AppContainer
        private set

    /**
     * Read lazily rather than built in `onCreate`, because WorkManager asks for it whenever a process
     * starts one of its components — which can be before this class has finished initialising, and in a
     * different order for a broadcast than for a launch.
     *
     * The factory is the point: a backup worker needs the dependency graph, and reflection cannot give
     * it one.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.ERROR)
            .setWorkerFactory(BackupWorkerFactory { container })
            .build()

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // The channel has to exist before the first upload asks for a notification, and a worker can be
        // scheduled the moment this process starts — creating it here rather than on first use is what
        // makes the difference invisible.
        container.backupNotifications.ensureChannel()

        // Staged copies belong to whichever process made them. Anything found now is left over from a
        // previous one, and holding a duplicate of a photo the user still has is a cost with no benefit.
        container.prepareStagingForBackup()
    }
}

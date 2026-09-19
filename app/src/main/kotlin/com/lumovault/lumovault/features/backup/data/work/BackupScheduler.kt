package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Enqueues [BackupWorker] runs.
 *
 * Wi-Fi-only lives in the WorkManager constraints ([NetworkType.UNMETERED]) —
 * the OS holds the job back, so the engine never needs to re-check
 * connectivity mid-upload. The periodic run only exists while background
 * backup is enabled; [reschedule] keeps the WorkManager state in step with
 * the settings toggle.
 */
@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val workManager get() = WorkManager.getInstance(context)

    /** One manual run — the dashboard's "Start backup". */
    fun runNow() {
        val request = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        workManager.enqueueUniqueWork(BackupWorker.ONESHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /**
     * Syncs the periodic schedule with the settings. The engine honors
     * charging/battery preferences itself at pick-up time; here only the
     * network class is constrained, so a user flipping Wi-Fi-only off and on
     * updates the constraint rather than leaving the stale one.
     */
    fun reschedule(backgroundEnabled: Boolean, wifiOnly: Boolean) {
        if (!backgroundEnabled) {
            workManager.cancelUniqueWork(BackupWorker.PERIODIC_WORK)
            return
        }
        val network = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(network).build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            BackupWorker.PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    companion object {
        /** Every 6 hours; a fresh scan + upload pass is cheap thanks to dedup. */
        const val PERIODIC_INTERVAL_HOURS = 6L
    }
}
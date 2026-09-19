package com.lumovault.lumovault.features.backup.data.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.lumovault.core.tdlib.ConnectionStatus
import com.lumovault.lumovault.core.tdlib.TdLibConnectionManager
import com.lumovault.lumovault.features.backup.data.service.BackupEngine
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * The background backup job: one [BackupEngine] pass per invocation.
 *
 * The connection manager is poked first — the TDLib client is torn down
 * between sessions on Android, and an engine pass over a dead client would
 * fail every upload with CLIENT_NOT_INITIALIZED.
 *
 * Retry mapping: auth/setup failures are permanent for this run (the user
 * must sign in or fix the network themselves); transient failures let
 * WorkManager's backoff reschedule, with the result marked so the run does
 * not restart an already-uploaded batch (dedup handles re-runs anyway).
 */
@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val engine: BackupEngine,
    private val connectionManager: TdLibConnectionManager,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            if (!connectionManager.isConnected) {
                connectionManager.connect()
            }
            engine.runOnce()
            Result.success()
        } catch (e: SecurityException) {
            Result.failure()
        } catch (e: Throwable) {
            if (connectionManager.status.value == ConnectionStatus.connected && runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        const val PERIODIC_WORK = "lumovault_backup_periodic"
        const val ONESHOT_WORK = "lumovault_backup_oneshot"
        const val MAX_ATTEMPTS = 3
    }
}
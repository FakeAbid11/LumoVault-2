package com.lumovault.app.data.backup

import android.content.Context
import androidx.work.BackoffCriteria
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lumovault.app.AppContainer
import java.util.concurrent.TimeUnit

/**
 * Asks for a pass over the backup queue.
 *
 * All this knows is how to schedule; what a pass does lives in [com.lumovault.app.domain.usecase.RunBackupQueueUseCase].
 * That split is what lets the same call serve a tap on "Back Up", a retry after a failure, and a
 * process restart — each of them is "there is work in the table, go", and none of them needs to know
 * whether a worker is already running.
 *
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] is the answer to that last question: if a pass is already
 * running it finishes and then runs again for whatever was added since, rather than being cancelled
 * mid-upload (which would waste the bytes already sent) or having this request dropped (which would
 * leave the new items sitting in a table nobody is looking at).
 *
 * The network constraint is what implements "offline means wait, not fail": work with no connection is
 * not started at all, so an item cannot be marked failed for a reason the phone could have known
 * before it tried.
 */
class BackupScheduler(private val context: Context) {

    fun start() {
        val request = OneTimeWorkRequestBuilder<BackupUploadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffCriteria.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, listOf(request))
    }

    /**
     * Stops queued passes. Items already claimed by a running worker are not interrupted from here —
     * the queue rows are cancelled separately, and what is genuinely in flight is left to finish, so a
     * tap on cancel cannot orphan a half-sent file.
     */
    fun stop() {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    private companion object {
        const val WORK_NAME = "lumovault-backup-queue"
        const val WORK_TAG = "lumovault-backup"

        /** Exponential from half a minute: quick enough to recover a brief dropout, slow enough to
         * stop a long outage turning into a request storm against Telegram. */
        const val FIRST_BACKOFF_SECONDS = 30L
    }
}

/**
 * Builds workers with the application's dependency graph rather than letting WorkManager try to
 * construct them reflectively.
 *
 * This is the reason the default initialiser is removed in the manifest: a worker built by reflection
 * cannot be handed [AppContainer], and the alternative — reaching through `applicationContext` for a
 * global — would put a service locator inside a worker that a test could never run.
 *
 * The container arrives as a provider rather than a value. WorkManager reads the configuration when its
 * process component starts, which can be before `Application.onCreate` has finished, so touching the
 * `lateinit` container here would crash the process at construction of an object that is never used. A
 * worker is only ever created after start-up has completed, so this defers the one step that is safe to
 * defer.
 */
class BackupWorkerFactory(private val container: () -> AppContainer) : WorkerFactory() {
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        BackupUploadWorker::class.java.name -> container().newBackupUploadWorker(workerParameters)
        // Anything else is WorkManager's own, and the default factory knows how to build it.
        else -> null
    }
}

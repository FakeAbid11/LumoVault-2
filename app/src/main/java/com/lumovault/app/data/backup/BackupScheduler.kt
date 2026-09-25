package com.lumovault.app.data.backup

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lumovault.app.AppContainer
import com.lumovault.app.domain.model.BackupPreferences
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

    /**
     * Asks for a pass under the loosest constraints the app will ever use: any network.
     *
     * This is the manual path, and it stays unconstrained on purpose. A user who selects twelve photos and
     * taps "Back Up" has agreed to the transfer on this phone, in this minute, on this connection — turning
     * the Wi-Fi preference into a wall in front of that tap would be the app overriding a decision the user
     * just made in front of it.
     */
    fun start() {
        enqueueUpload(connectedOnly())
    }

    /**
     * The same pass, requested by the automatic path, under the constraints the user configured.
     *
     * Wi-Fi-only and charging-only mean something here and nowhere else: they are statements about work the
     * user is not watching, so an unattended queue waits for an unmetered network and, if that is what was
     * asked, for a charger. WorkManager holds the request rather than failing it, which is why the queue can
     * report "waiting" as a state instead of a row of errors.
     */
    fun startAutomatic(preferences: BackupPreferences) {
        enqueueUpload(constraintsFor(preferences.toAutomaticWorkRequest()))
    }

    private fun enqueueUpload(constraints: Constraints) {
        val request = OneTimeWorkRequestBuilder<BackupUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, listOf(request))
    }

    /**
     * Installs or re-installs the periodic pass.
     *
     * Re-enqueued rather than left running when the settings change, because constraints are fixed on the
     * request: a user who turns on "back up while charging only" would otherwise wait until the next period
     * for a phone that is already behaving differently. [ExistingPeriodicWorkPolicy.UPDATE] replaces the
     * schedule and keeps the period, so the change takes effect without resetting the whole day's plan.
     */
    fun scheduleAutomaticPasses(preferences: BackupPreferences) {
        if (!preferences.automatic) {
            cancelAutomaticPasses()
            return
        }
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(
            PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(constraintsFor(preferences.toAutomaticWorkRequest()))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAutomaticPasses() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    private fun connectedOnly(): Constraints = constraintsFor(MANUAL_WORK)

    private fun constraintsFor(request: WorkRequest): Constraints = Constraints.Builder()
        .setRequiredNetworkType(
            if (request.requiresUnmeteredNetwork) NetworkType.UNMETERED else NetworkType.CONNECTED,
        )
        .setRequiresCharging(request.requiresCharging)
        .build()

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

        /** The periodic scan-and-queue pass. Its own name, so cancelling it never touches a send. */
        const val PERIODIC_WORK_NAME = "lumovault-automatic-backup"

        /**
         * Six hours: long enough that a phone which gains two photos a day is not woken to scan 90,000 rows
         * every fifteen minutes, short enough that a photo taken while the app was closed appears in the
         * library the same evening. WorkManager's floor is 15 minutes and its own default is 12 hours, and
         * neither is what a user means by "back up automatically".
         */
        const val PERIODIC_INTERVAL_HOURS = 6L

        /** What a hand-tapped backup is allowed to wait for: nothing but a connection. */
        val MANUAL_WORK = WorkRequest(requiresUnmeteredNetwork = false, requiresCharging = false)
    }
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
        AutomaticBackupWorker::class.java.name -> container().newAutomaticBackupWorker(workerParameters)
        // Anything else is WorkManager's own, and the default factory knows how to build it.
        else -> null
    }
}
}

/**
 * What a pass is willing to wait for, decided apart from WorkManager so the rule can be read and tested
 * without a context, a scheduler, or a device.
 *
 * The two callers differ in exactly this and nothing else: an unattended queue honours the user's Wi-Fi and
 * charging preferences, while a backup the user started by hand waits for a connection and nothing more.
 * A third row in this pair would be a bug either way: preferences applied to the manual path would leave a
 * tapped button doing nothing on mobile, and preferences ignored by the automatic path would spend a
 * metered connection the user said not to.
 */
internal data class WorkRequest(val requiresUnmeteredNetwork: Boolean, val requiresCharging: Boolean)

internal fun BackupPreferences.toAutomaticWorkRequest(): WorkRequest =
    WorkRequest(requiresUnmeteredNetwork = wifiOnly, requiresCharging = chargingOnly)


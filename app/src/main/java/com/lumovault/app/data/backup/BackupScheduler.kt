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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.lumovault.app.AppContainer
import com.lumovault.app.domain.model.BackupPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

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
        enqueueUpload(manualSendPlan())
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
        enqueueUpload(automaticSendPlan(preferences))
    }

    /**
     * The name is part of the constraint contract, not just a label.
     *
     * Both paths used to enqueue under one unique name with `APPEND_OR_REPLACE`, so a hand-tapped backup
     * arrived to find the unattended chain already there and joined it — and a chain waits for the
     * constraints of the work in it. Tapping "Back Up" on mobile data then meant waiting for a Wi-Fi
     * network the user had not agreed to use, which is the one outcome [BackupPreferences] promises is
     * impossible. Two names, two chains: the strict constraints can only ever hold up the work that
     * inherited them from a setting, never work that came from a tap.
     */
    private fun enqueueUpload(plan: PassPlan) {
        val request = OneTimeWorkRequestBuilder<BackupUploadWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .apply { plan.waitsFor?.let { waits -> setConstraints(constraintsFor(waits)) } }
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(plan.workName, ExistingWorkPolicy.APPEND_OR_REPLACE, listOf(request))
    }

    /**
     * Installs or re-installs the periodic scan, and takes it back out.
     *
     * Re-enqueued rather than left running when the settings change because this request either exists or
     * does not, and only the settings row says which. [ExistingPeriodicWorkPolicy.UPDATE] replaces the
     * schedule and keeps the period, so a person who has just turned the feature off is not woken by the pass
     * their previous answer installed.
     *
     * It carries no constraints, deliberately. This pass reads MediaStore and writes rows, and neither wants
     * a network or a charger: Wi-Fi-only and charging-only are promises about *sending*, and they are kept
     * where the bytes actually move, on [startAutomatic]. Bind this request by them instead and a phone off
     * the charger stops noticing new photos at all — nothing reaches the queue, so there is no waiting queue
     * for the Backup screen to explain, and the feature reads as switched off from a screen that says it is
     * on. [scanNow] runs the same worker with no constraints for the same reason.
     */
    fun scheduleAutomaticPasses(preferences: BackupPreferences) {
        if (!preferences.automatic) {
            cancelAutomaticPasses()
            return
        }
        val plan = periodicScanPlan()
        val request = PeriodicWorkRequestBuilder<AutomaticBackupWorker>(
            PERIODIC_INTERVAL_HOURS,
            TimeUnit.HOURS,
        )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .apply { plan.waitsFor?.let { waits -> setConstraints(constraintsFor(waits)) } }
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(plan.workName, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelAutomaticPasses() {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK_NAME)
    }

    /**
     * Whether the unattended send is currently sitting on a constraint it has not been met.
     *
     * Asked of WorkManager rather than deduced from the settings row, because the two are different questions:
     * the row says the user wants Wi-Fi, and only WorkManager knows whether the request is being held by that
     * wish right now. A screen that read the row alone would draw "waiting for Wi-Fi" over a queue draining
     * happily on the network, which is an invented state on the one surface whose job is reporting facts.
     *
     * Only the send chain is asked about. The scan chain carries no constraints since a preference bound to
     * the wrong chain meant photos went unnoticed rather than merely waiting, so it can never be held.
     */
    fun sendHeldByConstraints(): Flow<Boolean> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(AUTOMATIC_WORK_NAME)
            .map { infos -> infos.any { it.state == WorkInfo.State.BLOCKED } }

    /**
     * One scan-and-queue pass, now, in the background.
     *
     * Re-installing the periodic schedule is not enough after a folder save: on a six-hour period, "back up
     * this folder" would otherwise wait for the next period — or for a process restart, which is the only
     * other thing that re-reads the settings. So the same worker the period uses is asked for once,
     * immediately, and the queueing that follows does not depend on the app staying open.
     *
     * It carries its own unique name, for two reasons. WorkManager rejects a unique name that the other kind
     * of request already holds, so one-time scan work named like the periodic pass throws instead of
     * queuing; and `APPEND_OR_REPLACE` on a name of its own turns a second save during a running pass into
     * exactly one follow-up pass rather than two scans pruning each other's rows.
     *
     * No constraints, matching the periodic pass: reading MediaStore and writing rows needs neither a network
     * nor a charger. What Wi-Fi-only and charging-only govern is the *send* this pass may then ask for, which
     * is a different request under a different name — [startAutomatic].
     */
    fun scanNow() {
        val plan = immediateScanPlan()
        val request = OneTimeWorkRequestBuilder<AutomaticBackupWorker>()
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, FIRST_BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .apply { plan.waitsFor?.let { waits -> setConstraints(constraintsFor(waits)) } }
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(plan.workName, ExistingWorkPolicy.APPEND_OR_REPLACE, listOf(request))
    }

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
        // Both send chains: a tap on cancel means "stop uploading", and which of the two enqueued the row
        // makes no difference to the user. The periodic *scan* keeps its own name — cancelling a send is
        // not a way to switch off the setting that finds the next one.
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(MANUAL_WORK_NAME)
        work.cancelUniqueWork(AUTOMATIC_WORK_NAME)
    }

    private companion object {
        /**
         * Six hours: long enough that a phone which gains two photos a day is not woken to scan 90,000 rows
         * every fifteen minutes, short enough that a photo taken while the app was closed appears in the
         * library the same evening. WorkManager's floor is 15 minutes and its own default is 12 hours, and
         * neither is what a user means by "back up automatically".
         */
        const val PERIODIC_INTERVAL_HOURS = 6L

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

/** What a hand-tapped backup is allowed to wait for: nothing but a connection. */
internal val manualWorkRequest = WorkRequest(requiresUnmeteredNetwork = false, requiresCharging = false)

/**
 * The chains this app runs, named apart from WorkManager so which one waits for what can be read and tested
 * without a context or a device.
 *
 * These strings are load-bearing rather than labels: WorkManager keys unique work by name, so two passes that
 * share one name join one chain — and a chain waits for the constraints of the work inside it. That is how a
 * hand-tapped backup came to be held behind a Wi-Fi network the user had not agreed to use.
 */
internal const val MANUAL_WORK_NAME = "lumovault-backup-queue-manual"

/** The unattended send, and the only work the user's Wi-Fi and charging preferences ever bound. */
internal const val AUTOMATIC_WORK_NAME = "lumovault-backup-queue-automatic"

/** The periodic scan-and-queue pass. Its own name, so cancelling it never touches a send. */
internal const val PERIODIC_WORK_NAME = "lumovault-automatic-backup"

/**
 * The one-shot version of the same pass, asked for by a folder save. Its own name because WorkManager keys
 * unique work across both kinds: reusing [PERIODIC_WORK_NAME] for a one-time request fails.
 */
internal const val SCAN_NOW_WORK_NAME = "lumovault-automatic-backup-now"

/**
 * What one pass asks WorkManager for: which chain it belongs to, and what it will wait for.
 *
 * A null [waitsFor] means no constraints at all, which is a decision rather than an omission. The scan passes
 * only fill the queue and need neither a network nor a charger, so binding them by the user's preferences is how
 * "charging only" came to mean "nothing is ever noticed" — and a queue that never receives a row is a queue with
 * nothing for the Backup screen to explain, so the failure was invisible from both ends. These four functions are
 * the single place that answer is given, which is what lets a test say *which* chain a preference binds rather
 * than only that the preference was read correctly.
 */
internal data class PassPlan(val workName: String, val waitsFor: WorkRequest?)

/** The periodic scan. It takes no preferences on purpose: what it waits for must not depend on them. */
internal fun periodicScanPlan(): PassPlan = PassPlan(PERIODIC_WORK_NAME, waitsFor = null)

/** The same scan, asked for immediately by a folder save instead of on the period. */
internal fun immediateScanPlan(): PassPlan = PassPlan(SCAN_NOW_WORK_NAME, waitsFor = null)

/** The unattended send, held by exactly what the user ticked. */
internal fun automaticSendPlan(preferences: BackupPreferences): PassPlan =
    PassPlan(AUTOMATIC_WORK_NAME, preferences.toAutomaticWorkRequest())

/** The send a person started by hand: a connection and nothing else, because the tap is the agreement. */
internal fun manualSendPlan(): PassPlan = PassPlan(MANUAL_WORK_NAME, manualWorkRequest)

/**
 * Which promise is holding the unattended send, as far as the Backup screen is concerned.
 *
 * `None` covers both "not blocked" and "blocked by having no connection at all". The second of those is not a
 * sentence worth putting on screen: it names nothing the user asked for, the phone clears it by itself, and a
 * card that appears and disappears with the radio is a card people learn to ignore.
 */
internal enum class SendHold { None, WaitingForUnmetered, WaitingForCharger, WaitingForBoth }

/**
 * The hold, decided from both halves of the answer rather than one.
 *
 * [blocked] is WorkManager's own state and [request] is the user's settings, and neither alone is the truth:
 * the settings would report a wait that is not happening, and the state could not say what is being waited
 * for. Taking only the two flags this reads is the same pair [automaticSendPlan] puts on the request, so the
 * screen cannot drift into naming a constraint the enqueuer never applied.
 */
internal fun sendHoldFor(request: WorkRequest, blocked: Boolean): SendHold = when {
    !blocked -> SendHold.None
    request.requiresUnmeteredNetwork && request.requiresCharging -> SendHold.WaitingForBoth
    request.requiresUnmeteredNetwork -> SendHold.WaitingForUnmetered
    request.requiresCharging -> SendHold.WaitingForCharger
    else -> SendHold.None
}


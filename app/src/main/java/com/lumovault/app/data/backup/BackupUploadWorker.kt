package com.lumovault.app.data.backup

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.usecase.QueueRun
import com.lumovault.app.domain.usecase.RecognizeBackupUseCase
import com.lumovault.app.domain.usecase.RunBackupQueueUseCase
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.supervisorScope

/**
 * Runs one pass over the backup queue as foreground work.
 *
 * Foreground because an upload is the one thing in this app that can legitimately run for minutes at a
 * time: a background worker gets a completion window measured in tens of seconds, and a queue that was
 * stopped mid-video would be indistinguishable from one that failed. `dataSync` is the honest type,
 * declared on the service in the manifest and repeated here because Android 14+ requires both.
 *
 * The notification is refreshed from the queue's own Room query rather than from anything this worker
 * counts. That is not laziness: it means the number in the shade and the number on the screen are read
 * from the same rows, so they cannot tell the user different stories about the same upload.
 */
class BackupUploadWorker(
    context: Context,
    parameters: WorkerParameters,
    private val queue: BackupQueueRepository,
    private val runner: RunBackupQueueUseCase,
    private val recognizer: RecognizeBackupUseCase,
    private val notifications: BackupNotifications,
) : CoroutineWorker(context, parameters) {

    @Volatile
    private var summary = BackupQueueSummary()

    @Volatile
    private var label = ""

    @Volatile
    private var percent: Int? = null

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(
            NOTIFICATION_ID,
            notifications.build(summary, label, percent),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

    override suspend fun doWork(): Result = supervisorScope {
        // Collected as a child of this scope: the notifier only posts status, so a failure inside it
        // must not end — or be mistaken for — an upload.
        //
        // The Job is kept because `supervisorScope` waits for its children before it returns, and this one
        // is an infinite Room flow. Left running, `doWork` never finishes after the queue drains, and the
        // dataSync foreground service — with its "backing up" notification — stays up until the system
        // kills it, which is exactly the permanent foreground service PRD section 62 rules out.
        val notifier = queue.observeSummary()
            .onEach { latest ->
                summary = latest
                setForeground(getForegroundInfo())
            }
            .launchIn(this)

        try {
            // Recognition runs first and on purpose: an item that is about to be sent may turn out to be
            // already stored, and finding that out before the upload rather than after it is what keeps a
            // duplicate out of the user's channel. It is bounded — it yields to the queue the moment anything
            // is queued — so this is never a reason an upload waits.
            val recognition = recognizer.run()

            val outcome = runner.run { progress ->
                label = progress.displayName
                // Rounded, so a burst of TDLib file updates becomes a handful of notifications rather than
                // one per buffer. Nothing else in the app needs byte resolution.
                val rounded = (progress.fraction * 100f).toInt().coerceIn(0, 100)
                if (percent != rounded) {
                    percent = rounded
                    setForeground(getForegroundInfo())
                }
            }

            // What WorkManager is asked for next is decided entirely by [toPassDirective], which is a
            // function over the pass's outcome rather than a branch in here — so the table, including the
            // bound on retrying a refusal a person has to clear, is readable and testable without a worker.
            when (outcome.toPassDirective(runAttemptCount, recognition.stoppedEarly)) {
                PassDirective.TryAgain -> Result.retry()
                PassDirective.Finished -> Result.success()

                // The chain is ending with work still waiting, and the only party that can clear the reason
                // is somebody who has been told what it is. This call is the whole of that telling — before
                // it, a `Result.failure()` here was invisible from inside the app and from the shade alike.
                PassDirective.Abandoned -> {
                    notifications.notifyStopped(outcome)
                    Result.failure()
                }
            }
        } finally {
            notifier.cancel()
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4100
    }
}

/** What to ask WorkManager for next, which is the whole decision a pass makes when it ends. */
internal enum class PassDirective { Finished, TryAgain, Abandoned }

/**
 * The pass's outcome turned into a scheduling decision, written apart from WorkManager so the table can
 * be read — and tested — without a worker.
 *
 * [QueueRun.Done.deferred] is the retry signal: an item that Telegram or the network refused is back in
 * the queue, and WorkManager's exponential backoff is what spaces the attempts out — this worker must not
 * sit in a loop doing it faster and worse. A recognition pass that ran out of its time budget borrows the
 * same signal rather than scheduling work of its own. The frontier strictly shrinks — every item it reaches
 * either gets a hash or is recorded as unreadable, and both leave the candidate set — so asking to be run
 * again terminates, which is what makes a second worker name unnecessary here.
 *
 * A pass that stopped because the account is signed out, or because no backup channel has been adopted yet,
 * stopped for a reason a *person* has to clear. Ending the chain on the first of those would leave a phone
 * whose owner signs in later with no work scheduled at all until the next periodic pass — six hours of
 * nothing, with a queue full of photos. So each is retried and the backoff does the waiting, bounded so a
 * decision the user never reverses cannot become a permanent wake-up: at this chain's curve — exponential
 * from half a minute, set in [BackupScheduler] — the three attempts add up to a few minutes and then the
 * chain ends, leaving the reason to be said out loud rather than re-asked forever.
 *
 * [QueueRun.TelegramUnavailable] is not in that family: it says this build has no TDLib or no API
 * credentials, which neither a retry nor a user can change.
 */
internal fun QueueRun.toPassDirective(attempt: Int, recognitionStoppedEarly: Boolean): PassDirective = when (this) {
    is QueueRun.Done -> when {
        deferred || recognitionStoppedEarly -> PassDirective.TryAgain
        else -> PassDirective.Finished
    }

    QueueRun.NoChannel, QueueRun.SignedOut ->
        if (attempt < STOP_DIRECTIVE_ATTEMPT_LIMIT) PassDirective.TryAgain else PassDirective.Abandoned

    QueueRun.TelegramUnavailable -> PassDirective.Abandoned
}

/** How many times a pass that needs a human may ask to be run again before it says so and stops. */
internal const val STOP_DIRECTIVE_ATTEMPT_LIMIT = 3

package com.lumovault.app.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.app.domain.usecase.RunAutomaticBackupUseCase

/**
 * The periodic pass: notice new media and put it in the queue.
 *
 * Deliberately *not* the uploader. Splitting the two is what lets each be constrained by what it actually
 * needs: scanning MediaStore and writing rows wants no network and no charger, while sending bytes wants
 * both — and a worker that did both under the union of their constraints would refuse to notice a new photo
 * on a phone that is off Wi-Fi, which is the opposite of what the user asked for when they turned the
 * feature on.
 *
 * It is also short enough to run in the background without a notification or a foreground service: it does
 * one window of work and finishes, and PRD section 39's "long-running operations may require a foreground
 * strategy" is satisfied by the upload worker, which is where the minutes actually go.
 *
 * WorkManager may run this with no constraints met at all after a reboot or a force-start; the use case
 * re-reads the settings every time, so a pass that begins while the phone is still on mobile simply queues
 * and leaves the sending to a worker that waits.
 */
class AutomaticBackupWorker(
    context: Context,
    parameters: WorkerParameters,
    private val runPass: suspend () -> RunAutomaticBackupUseCase.Outcome,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result = when (val outcome = runPass()) {
        // The grant can come back without another periodic period elapsing, so this is worth another try
        // rather than a silent success over a library nobody scanned — but a bounded number of them. A
        // person who chose "Don't allow" and meant it would otherwise be woken every backoff interval,
        // forever, by work that can only ever come back with the same answer.
        RunAutomaticBackupUseCase.Outcome.NoMediaAccess ->
            if (runAttemptCount < ACCESS_RETRY_LIMIT) Result.retry() else Result.success()

        // Off, or never configured: nothing to do, and nothing to retry.
        RunAutomaticBackupUseCase.Outcome.Disabled,
        RunAutomaticBackupUseCase.Outcome.NoSourceSelected,
        -> Result.success()

        is RunAutomaticBackupUseCase.Outcome.Queued ->
            // More work remains only when the window filled, which is the pass saying "keep going" to
            // itself rather than the app pretending a hundred-thousand-photo library was one pass.
            if (outcome.moreRemaining) Result.retry() else Result.success()
    }

    private companion object {
        /** Roughly an hour of retries at this worker's backoff curve, then the next period decides. */
        const val ACCESS_RETRY_LIMIT = 6
    }
}

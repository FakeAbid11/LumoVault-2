package com.lumovault.app.data.backup

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lumovault.app.domain.usecase.RunAutomaticBackupUseCase
import kotlinx.coroutines.CancellationException

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

    override suspend fun doWork(): Result = try {
        when (runPass().toAutoPassDirective(runAttemptCount)) {
            AutoPassDirective.TryAgain -> Result.retry()
            AutoPassDirective.Finished -> Result.success()
        }
    } catch (cancelled: CancellationException) {
        // The system stopping the worker is not the pass failing, and WorkManager already knows the
        // difference: a cancelled coroutine ends the work as cancelled.
        throw cancelled
    } catch (error: Exception) {
        // WorkManager logs a throwable whole on the way to `Result.failure()`; a MediaStore or SQLite
        // message can quote a path, so only the class name is kept. The outcome is the one WorkManager
        // would have produced anyway — this pass failed, and the next period decides again — because
        // reporting success over a scan that threw would promise a scan that never ran. A throw is not an
        // [RunAutomaticBackupUseCase.Outcome], which is why this stays outside the mapping.
        Log.w(TAG, "automatic backup pass failed: ${error.javaClass.simpleName}")
        Result.failure()
    }

    private companion object {
        private const val TAG = "LumoVaultAutoBackup"
    }
}

/** What one finished scan pass asks for next. */
internal enum class AutoPassDirective { Finished, TryAgain }

/**
 * How many times a pass that could not read the library asks again.
 *
 * Roughly half an hour of retries at this worker's thirty-second exponential curve (30+60+120+240+480+960
 * seconds), then the next period decides. Six is not an hour and the count is here so the arithmetic is read
 * next to the number rather than in a comment about it.
 */
internal const val ACCESS_RETRY_LIMIT = 6

/**
 * What an ended pass means, decided apart from WorkManager so the answer can be tested without a context.
 *
 * This is the whole retry policy of the unattended scan, and it used to be inline in [AutomaticBackupWorker]'s
 * `try`, which meant the one branch a user would ever notice — the queue still half full — was the only one with
 * no test beside it.
 */
internal fun RunAutomaticBackupUseCase.Outcome.toAutoPassDirective(attempt: Int): AutoPassDirective =
    when (this) {
        // The grant can come back without another periodic period elapsing, so this is worth another try
        // rather than a silent success over a library nobody scanned — but a bounded number of them. A person
        // who chose "Don't allow" and meant it would otherwise be woken every backoff interval, forever, by
        // work that can only ever come back with the same answer.
        RunAutomaticBackupUseCase.Outcome.NoMediaAccess ->
            if (attempt < ACCESS_RETRY_LIMIT) AutoPassDirective.TryAgain else AutoPassDirective.Finished

        // Off, or never configured: nothing to do, and nothing to retry.
        RunAutomaticBackupUseCase.Outcome.Disabled,
        RunAutomaticBackupUseCase.Outcome.NoSourceSelected,
        -> AutoPassDirective.Finished

        // More work remains only when the window filled, which is the pass saying "keep going" to itself
        // rather than the app pretending a hundred-thousand-photo library was one pass.
        is RunAutomaticBackupUseCase.Outcome.Queued ->
            if (moreRemaining) AutoPassDirective.TryAgain else AutoPassDirective.Finished
    }

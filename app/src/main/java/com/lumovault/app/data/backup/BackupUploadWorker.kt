package com.lumovault.app.data.backup

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.usecase.QueueRun
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
        queue.observeSummary()
            .onEach { latest ->
                summary = latest
                setForeground(getForegroundInfo())
            }
            .launchIn(this)

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

        return@supervisorScope when (outcome) {
            // [deferred] is the retry signal: an item that Telegram or the network refused is back in
            // the queue, and WorkManager's exponential backoff is what spaces the attempts out — this
            // worker must not sit in a loop doing it faster and worse.
            is QueueRun.Done -> if (outcome.deferred) Result.retry() else Result.success()

            // Nothing to do about either from here: the user has to sign in, or the build has no
            // Telegram. Retrying would re-run a pass that cannot make progress.
            QueueRun.NoChannel, QueueRun.TelegramUnavailable -> Result.failure()
        }
    }

    private companion object {
        const val NOTIFICATION_ID = 4100
    }
}

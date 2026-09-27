package com.lumovault.app.data.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.lumovault.app.MainActivity
import com.lumovault.app.R
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.usecase.QueueRun

/**
 * The app's notifications: the progress one a foreground service has to show, and the one it posts when a
 * queue with work in it has to stop for something only the user can clear.
 *
 * It exists because a foreground service must show one, and because "Backing up 3 of 10" is worth
 * seeing when the app is not in front of the user. It is low importance on purpose: an upload that is
 * going fine has nothing to say, and a channel that pings per item trains the user to dismiss
 * LumoVault rather than read it.
 *
 * The counts come from the same queue query the on-screen progress line reads, so the notification and
 * the UI cannot disagree about how far along the queue is.
 *
 * Built with the platform builder rather than `NotificationCompat`: the minimum SDK already has every
 * API used here, and adding a dependency to reach a compatibility shim for a version the app does not
 * support would be the long way round to the same notification.
 */
class BackupNotifications(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.backup_channel_name),
                // Low, not default: this is a status readout, not an event. The channel's importance is
                // what makes it quiet on API 26+, so no per-notification priority is set either.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.backup_channel_description)
                setShowBadge(false)
            },
        )
    }

    fun build(summary: BackupQueueSummary, currentLabel: String, percent: Int?): Notification {
        val total = summary.total.coerceAtLeast(1)
        val done = (summary.backedUp + summary.failed).coerceAtMost(total)

        val count = context.getString(R.string.backup_progress_count, minOf(done + 1, total), total)
        val body = when {
            summary.inFlight == 0 && summary.queued > 0 -> context.getString(R.string.backup_preparing)
            percent != null -> context.getString(R.string.backup_progress_percent, percent)
            else -> count
        }

        val text = if (currentLabel.isBlank()) body else "$body · $currentLabel"

        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(context.getString(R.string.backup_progress_title))
            .setContentText(text)
            .setProgress(total, done, false)
            .setOngoing(true)
            // Re-issuing this notification must not buzz again; it updates as the queue moves. The
            // channel's LOW importance is what keeps it silent, so no per-notification silence call.
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
            .build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // Immutability is required on every supported version and correct here: nothing fills this
        // intent in later.
        return PendingIntent.getActivity(context, REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE)
    }

    /**
     * The one message this app ends a chain with, when the chain ended on something only the user can
     * clear and there is still work in the queue.
     *
     * It exists because the alternative was a `Result.failure()` that no screen and no shade ever
     * mentioned: the pass stopped, the photos stayed, and "the app does not back up" was the whole of what
     * anybody could tell. Same words as the card on the Backup screen, decided in the same place — one
     * reason, one sentence, so a notification and a screen cannot tell different stories.
     *
     * Dismissable and posted once per chain, not per attempt: a refusal that is re-raised every backoff
     * interval is the noise that trains somebody to swipe LumoVault away without reading it. The live
     * [NotificationManager.areNotificationsEnabled] check covers both the channel being silenced and the
     * runtime permission being withheld on API 33+, so this is a no-op rather than a crash on a phone where
     * notifications were never wanted.
     */
    fun notifyStopped(reason: QueueRun) {
        val copy = copyFor(reason) ?: return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentTitle(context.getString(copy.first))
            .setContentText(context.getString(copy.second))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(copy.second)))
            .setCategory(Notification.CATEGORY_ERROR)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

        runCatching { manager.notify(STOP_NOTIFICATION_ID, notification) }
    }

    /** The two sentences the Backup screen shows for the same refusal, so the two cannot disagree. */
    private fun copyFor(reason: QueueRun): Pair<Int, Int>? = when (reason) {
        QueueRun.NoChannel ->
            R.string.backup_blocked_no_channel_title to R.string.backup_blocked_no_channel_body

        QueueRun.SignedOut ->
            R.string.backup_blocked_signed_out_title to R.string.backup_blocked_signed_out_body

        QueueRun.TelegramUnavailable ->
            R.string.backup_blocked_unbuilt_title to R.string.backup_blocked_unbuilt_body

        // Anything that sent, or refused item by item, has its own count in the progress notification and
        // on the screen. There is nothing extra to say from here.
        is QueueRun.Done -> null
    }

    private companion object {
        const val CHANNEL_ID = "lumovault.backup"
        const val REQUEST_CODE = 4100

        /** Apart from the progress one, so a stopped queue can be read after the in-flight row is gone. */
        const val STOP_NOTIFICATION_ID = 4101
    }
}

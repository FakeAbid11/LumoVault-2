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

/**
 * The one notification the app has ever posted.
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

        val count = context.getString(R.string.backup_progress_count, done + 1, total)
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

    private companion object {
        const val CHANNEL_ID = "lumovault.backup"
        const val REQUEST_CODE = 4100
    }
}

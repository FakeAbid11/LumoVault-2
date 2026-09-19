package com.lumovault.lumovault.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.lumovault.lumovault.MainActivity
import com.lumovault.lumovault.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification type with its channel configuration.
 */
enum class NotificationType(
    val channelId: String,
    val channelName: String,
    val channelDescription: String,
    val priority: Int,
    val notificationId: Int,
) {
    BackupProgress(
        channelId = "backup_progress",
        channelName = "Backup Progress",
        channelDescription = "Shows ongoing backup progress",
        priority = NotificationCompat.PRIORITY_LOW,
        notificationId = NOTIFICATION_ID_PROGRESS,
    ),
    BackupCompleted(
        channelId = "backup_completed",
        channelName = "Backup Completed",
        channelDescription = "Notifies when backup finishes successfully",
        priority = NotificationCompat.PRIORITY_DEFAULT,
        notificationId = NOTIFICATION_ID_COMPLETED,
    ),
    BackupFailed(
        channelId = "backup_failed",
        channelName = "Backup Failed",
        channelDescription = "Notifies when backup fails",
        priority = NotificationCompat.PRIORITY_HIGH,
        notificationId = NOTIFICATION_ID_FAILED,
    ),
    RestoreCompleted(
        channelId = "restore_completed",
        channelName = "Restore Completed",
        channelDescription = "Notifies when restore finishes",
        priority = NotificationCompat.PRIORITY_DEFAULT,
        notificationId = NOTIFICATION_ID_RESTORE,
    ),
    StorageWarning(
        channelId = "storage_warning",
        channelName = "Storage Warning",
        channelDescription = "Warns about low storage",
        priority = NotificationCompat.PRIORITY_HIGH,
        notificationId = NOTIFICATION_ID_STORAGE,
    ),
}

/**
 * Manages local notifications for backup progress, completion, and failure.
 *
 * Ported from Flutter `lib/core/notifications/notification_service.dart`.
 *
 * Creates 5 Android notification channels on [initialize]. Each channel
 * has its own importance level and persistent notification ID. Progress
 * notifications are ongoing and share the foreground service ID.
 */
@Singleton
class NotificationService @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val notificationManager: NotificationManager
        get() = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /**
     * Create all notification channels. Call once at app startup.
     */
    fun initialize() {
        val channels = NotificationType.entries.map { type ->
            NotificationChannel(
                type.channelId,
                type.channelName,
                when (type.priority) {
                    NotificationCompat.PRIORITY_HIGH -> NotificationManager.IMPORTANCE_HIGH
                    NotificationCompat.PRIORITY_LOW -> NotificationManager.IMPORTANCE_LOW
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                },
            ).apply {
                description = type.channelDescription
            }
        }
        notificationManager.createNotificationChannels(channels)
    }

    /**
     * Request notification permission (Android 13+). Returns true if already
     * granted or on older APIs.
     */
    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationManager.areNotificationsEnabled()
        } else {
            true
        }
    }

    /**
     * Show an ongoing backup progress notification.
     *
     * @param percent Progress percentage (0-100).
     * @param count Number of items uploaded so far.
     * @param total Total items to upload.
     * @param fileName Name of the file currently being uploaded.
     */
    fun showBackupProgress(percent: Int, count: Int, total: Int, fileName: String) {
        val type = NotificationType.BackupProgress
        val title = "Backing up photos..."
        val text = "$count / $total ($percent%) — $fileName"

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(type.priority)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(type.notificationId, notification)
    }

    /**
     * Show backup completion notification, cancelling the progress one.
     */
    fun showBackupCompleted(count: Int, bytesUploaded: Long) {
        cancelNotification(NotificationType.BackupProgress)

        val type = NotificationType.BackupCompleted
        val title = "Backup complete"
        val text = "$count photos backed up (${formatBytes(bytesUploaded)})"

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(type.priority)
            .build()

        notificationManager.notify(type.notificationId, notification)
    }

    /**
     * Show backup failure notification, cancelling the progress one.
     */
    fun showBackupFailed(error: String) {
        cancelNotification(NotificationType.BackupProgress)

        val type = NotificationType.BackupFailed
        val title = "Backup failed"
        val text = error.take(200)

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(type.priority)
            .build()

        notificationManager.notify(type.notificationId, notification)
    }

    /**
     * Show restore completion notification.
     */
    fun showRestoreCompleted(count: Int) {
        val type = NotificationType.RestoreCompleted
        val title = "Restore complete"
        val text = "$count photos restored"

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setPriority(type.priority)
            .build()

        notificationManager.notify(type.notificationId, notification)
    }

    /**
     * Show a storage warning notification.
     */
    fun showStorageWarning(message: String) {
        val type = NotificationType.StorageWarning

        val notification = NotificationCompat.Builder(context, type.channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Storage warning")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(type.priority)
            .build()

        notificationManager.notify(type.notificationId, notification)
    }

    /** Cancel a specific notification type. */
    fun cancelNotification(type: NotificationType) {
        notificationManager.cancel(type.notificationId)
    }

    /** Cancel all LumoVault notifications. */
    fun cancelAll() {
        NotificationType.entries.forEach { notificationManager.cancel(it.notificationId) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    companion object {
        private const val NOTIFICATION_ID_PROGRESS = 1001
        private const val NOTIFICATION_ID_COMPLETED = 1002
        private const val NOTIFICATION_ID_FAILED = 1003
        private const val NOTIFICATION_ID_RESTORE = 1004
        private const val NOTIFICATION_ID_STORAGE = 1005
    }
}

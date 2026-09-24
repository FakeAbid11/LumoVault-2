package com.lumovault.app.data.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.repository.PermissionRepository

/**
 * Android changed its media permission model at API 33 (typed media permissions) and again at 34
 * ("selected photos"), so the ask is version-dependent. A single legacy `READ_EXTERNAL_STORAGE`
 * would either be ignored on new devices or over-ask on old ones.
 */
class SystemPermissionsRepositoryImpl(context: Context) : PermissionRepository {
    private val appContext = context.applicationContext

    override fun mediaPermissionsToRequest(): List<String> = when {
        atLeast(API_34) -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            // Lets "some of my photos" be an explicit outcome rather than an all-or-nothing ask.
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )

        atLeast(API_33) -> listOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )

        else -> listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    override fun mediaStatus(): MediaAccessStatus {
        val images = isGranted(Manifest.permission.READ_MEDIA_IMAGES)
        val videos = isGranted(Manifest.permission.READ_MEDIA_VIDEO)

        return when {
            atLeast(API_34) -> when {
                images && videos -> MediaAccessStatus.Granted
                // Either full media types or the user-selected subset counts as usable access.
                images || videos || isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ->
                    MediaAccessStatus.PartiallyGranted

                else -> MediaAccessStatus.Denied
            }

            atLeast(API_33) -> if (images || videos) MediaAccessStatus.Granted else MediaAccessStatus.Denied

            else -> if (isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)) {
                MediaAccessStatus.Granted
            } else {
                MediaAccessStatus.Denied
            }
        }
    }

    override fun notificationsStatus(): NotificationsStatus {
        if (!atLeast(API_33)) return NotificationsStatus.NotRequired

        return if (isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            NotificationsStatus.Granted
        } else {
            NotificationsStatus.Denied
        }
    }

    override fun backgroundBackupStatus(): BackgroundBackupStatus {
        val powerManager = appContext.getSystemService(PowerManager::class.java)
            ?: return BackgroundBackupStatus.Unknown

        return if (powerManager.isIgnoringBatteryOptimizations(appContext.packageName)) {
            BackgroundBackupStatus.Unrestricted
        } else {
            BackgroundBackupStatus.Restricted
        }
    }

    override fun canOpenBatterySettings(): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        // Package visibility on Android 11+ means an unresolvable intent here really is a device
        // without the page, which is why the manifest declares this action under <queries>.
        return intent.resolveActivity(appContext.packageManager) != null
    }

    private fun isGranted(permission: String): Boolean =
        appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun atLeast(apiLevel: Int): Boolean = Build.VERSION.SDK_INT >= apiLevel

    private companion object {
        const val API_33 = 33
        const val API_34 = 34
    }
}

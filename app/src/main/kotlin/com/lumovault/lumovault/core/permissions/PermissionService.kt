package com.lumovault.lumovault.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Media-library permission handling.
 *
 * Android 13 split photo/video access into READ_MEDIA_IMAGES /
 * READ_MEDIA_VIDEO; older versions use READ_EXTERNAL_STORAGE. The
 * [visualPermission] list is what a gallery scan actually needs, and
 * [ACCESS_MEDIA_LOCATION] is requested so EXIF lat/long survive on API 29+.
 */
class PermissionService(private val context: Context) {

    val visualPermissions: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    /** All permissions the app uses, for the onboarding screen's request flow. */
    val allPermissions: List<String> = buildList {
        addAll(visualPermissions)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        add(Manifest.permission.ACCESS_NETWORK_STATE)
        add(Manifest.permission.WAKE_LOCK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun hasVisualPermission(): Boolean = visualPermissions.all { isGranted(it) }

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    /** Permissions from [allPermissions] that still need granting. */
    fun missing(): List<String> = allPermissions.filterNot { isGranted(it) }
}

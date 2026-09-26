package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus

/**
 * Reads the setup state Android actually reports, rather than trusting a remembered answer: a
 * permission granted during onboarding can be revoked from system settings a week later, and a
 * stale "granted" flag would turn the Ready screen's ticks into lies.
 */
interface PermissionRepository {
    /**
     * The runtime permissions to ask for on *this* Android version. Empty means nothing to request,
     * which the UI must treat as already satisfied rather than as a failure.
     */
    fun mediaPermissionsToRequest(): List<String>

    fun mediaStatus(): MediaAccessStatus

    /**
     * The one permission the photo map needs and the library does not: `ACCESS_MEDIA_LOCATION`.
     *
     * It is a *media* permission, not a device-location one — it does not let LumoVault learn where the
     * phone is, only whether it may read the coordinates a photo already carries. Without it Android hands
     * the app the same file with its GPS EXIF stripped, so the camera and dates still read fine and the map
     * simply has nothing to place. Asked for only from inside the map, and only when the map turns out to be
     * empty for the reason this permission explains.
     */
    fun mediaLocationPermissionToRequest(): String

    /** The live answer, because the grant can be revoked from system settings while LumoVault is closed. */
    fun mediaLocationGranted(): Boolean

    fun notificationsStatus(): NotificationsStatus

    fun backgroundBackupStatus(): BackgroundBackupStatus

    /**
     * Whether this device actually offers the battery page the setup card opens. When it does not,
     * the card says so instead of showing a button that goes nowhere.
     */
    fun canOpenBatterySettings(): Boolean
}

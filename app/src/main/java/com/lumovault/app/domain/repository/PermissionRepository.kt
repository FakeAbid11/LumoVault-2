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

    fun notificationsStatus(): NotificationsStatus

    fun backgroundBackupStatus(): BackgroundBackupStatus

    /**
     * Whether this device actually offers the battery page the setup card opens. When it does not,
     * the card says so instead of showing a button that goes nowhere.
     */
    fun canOpenBatterySettings(): Boolean
}

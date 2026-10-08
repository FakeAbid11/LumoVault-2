package com.lumovault.app.ui.settings

import androidx.annotation.StringRes
import com.lumovault.app.R
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.ui.components.PillTone

/**
 * The four permissions LumoVault can hold, named so a caller can find the row it wants without matching
 * on a string resource.
 *
 * The marks themselves are drawn by the screen, not decided here: an [androidx.compose.ui.graphics.vector.ImageVector]
 * is not something a JVM test can build, and a decision this file makes has to be assertable off-device.
 */
internal enum class PermissionKind {
    Media,
    Notifications,

    /** `ACCESS_MEDIA_LOCATION` — the one the photo map needs and the library does not. */
    PhotoLocation,
    BackgroundBackup,
}

/** What one permission's row claims, and what — if anything — it can offer as a way out. */
internal enum class PermissionFix {
    /** Nothing is missing. */
    None,

    /** Only the app's system-settings page can change this now. */
    OpenAppSettings,

    /** Not a permission at all — a battery restriction, which has its own page. */
    OpenBatterySettings,

    /** This device offers no page that could change it, so there is nothing to send the user to. */
    Unavailable,
}

/** One permission, as the screen will print it. */
internal data class PermissionRow(
    val kind: PermissionKind,
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    @StringRes val statusRes: Int,
    val tone: PillTone,
    val fix: PermissionFix,
    @StringRes val fixLabelRes: Int,
    /** The sentence that replaces the button when there is nothing to press, or null to print nothing. */
    @StringRes val fixNoteRes: Int?,
)

/**
 * The whole Permissions screen's content, decided over Android's five answers in one place.
 *
 * It is a function rather than four composable bodies because the four rows share one rule — "is this
 * satisfied, and if not, what can the user actually press?" — and a row that answers it slightly
 * differently is how a screen ends up offering a way out that goes nowhere. All five inputs come from
 * [com.lumovault.app.domain.repository.PermissionRepository], so nothing here is a remembered grant: this
 * is the composition of those answers, not a second opinion on them.
 *
 * Two rules run through all four rows. Nothing here re-requests a permission — this is not onboarding, and
 * an unsolicited system dialog from a settings screen is the behaviour that makes "Don't ask again"
 * necessary in the first place — so the only thing on offer is a real page. And a satisfied permission
 * offers nothing: an enabled button for a grant the user already gave is not a shortcut, it is a second
 * copy of a decision already made.
 */
internal fun permissionRowsFor(
    media: MediaAccessStatus,
    notifications: NotificationsStatus,
    photoLocationGranted: Boolean,
    backgroundBackup: BackgroundBackupStatus,
    canOpenBatterySettings: Boolean,
): List<PermissionRow> = listOf(
    mediaRow(media),
    notificationsRow(notifications),
    photoLocationRow(photoLocationGranted),
    backgroundRow(backgroundBackup, canOpenBatterySettings),
)

private fun mediaRow(media: MediaAccessStatus) = PermissionRow(
    kind = PermissionKind.Media,
    labelRes = R.string.permissions_media,
    // The setup card's own sentence, reused rather than paraphrased: the reason LumoVault wants the
    // library does not change because the user is answering in a different screen.
    descriptionRes = R.string.setup_media_description,
    statusRes = when (media) {
        MediaAccessStatus.Granted -> R.string.status_allowed
        MediaAccessStatus.PartiallyGranted -> R.string.status_partly_allowed
        MediaAccessStatus.Denied, MediaAccessStatus.Unknown -> R.string.status_not_set
    },
    tone = when (media) {
        MediaAccessStatus.Granted, MediaAccessStatus.PartiallyGranted -> PillTone.Done
        MediaAccessStatus.Denied, MediaAccessStatus.Unknown -> PillTone.Missing
    },
    // Partial access is satisfied: it found part of the library and that is exactly what Android reported,
    // so the row offers no way out. The user chose which photos to share, and a button inviting them to
    // revisit that would be a suggestion, not a repair.
    fix = if (media.allowsScanning) PermissionFix.None else PermissionFix.OpenAppSettings,
    fixLabelRes = R.string.permissions_open_system,
    fixNoteRes = null,
)

private fun notificationsRow(notifications: NotificationsStatus) = PermissionRow(
    kind = PermissionKind.Notifications,
    labelRes = R.string.permissions_notifications,
    descriptionRes = if (notifications == NotificationsStatus.NotRequired) {
        R.string.setup_notifications_not_required
    } else {
        R.string.setup_notifications_description
    },
    statusRes = when (notifications) {
        NotificationsStatus.Granted -> R.string.status_allowed
        NotificationsStatus.NotRequired -> R.string.status_not_required
        NotificationsStatus.Denied -> R.string.status_skipped
        NotificationsStatus.Unknown -> R.string.status_not_set
    },
    tone = when (notifications) {
        NotificationsStatus.Granted, NotificationsStatus.NotRequired -> PillTone.Done
        NotificationsStatus.Denied -> PillTone.Skipped
        NotificationsStatus.Unknown -> PillTone.Missing
    },
    // "Not required" is an answer rather than a gap: a version with no such permission has no dialog to
    // re-open, so offering one would be a button that cannot do anything.
    fix = if (notifications == NotificationsStatus.NotRequired) {
        PermissionFix.None
    } else {
        PermissionFix.OpenAppSettings
    },
    fixLabelRes = R.string.permissions_open_system,
    fixNoteRes = null,
)

private fun photoLocationRow(granted: Boolean) = PermissionRow(
    kind = PermissionKind.PhotoLocation,
    labelRes = R.string.permissions_photo_location,
    descriptionRes = R.string.permissions_photo_location_description,
    statusRes = if (granted) R.string.status_allowed else R.string.status_not_set,
    tone = if (granted) PillTone.Done else PillTone.Missing,
    // Never urgent and never fatal: without it the map simply cannot place photographs, and the map asks
    // for it from inside itself where the reason is visible. This row exists so Settings is not the one
    // screen in the app that cannot say why the map is empty.
    fix = if (granted) PermissionFix.None else PermissionFix.OpenAppSettings,
    fixLabelRes = R.string.permissions_open_system,
    fixNoteRes = null,
)

private fun backgroundRow(
    backgroundBackup: BackgroundBackupStatus,
    canOpenBatterySettings: Boolean,
) = PermissionRow(
    kind = PermissionKind.BackgroundBackup,
    labelRes = R.string.permissions_background,
    descriptionRes = R.string.setup_background_description,
    statusRes = when (backgroundBackup) {
        BackgroundBackupStatus.Unrestricted -> R.string.status_unrestricted
        BackgroundBackupStatus.Restricted -> R.string.status_restricted
        BackgroundBackupStatus.Unknown -> R.string.status_unavailable
    },
    tone = when (backgroundBackup) {
        BackgroundBackupStatus.Unrestricted -> PillTone.Done
        BackgroundBackupStatus.Restricted -> PillTone.Missing
        BackgroundBackupStatus.Unknown -> PillTone.Unavailable
    },
    // Three answers, not two, and the middle one is about the device rather than the user: a phone that
    // offers no battery page cannot be sent anywhere useful, so the row says that in words rather than
    // pretending the trip would work.
    fix = when {
        backgroundBackup == BackgroundBackupStatus.Unrestricted -> PermissionFix.None
        canOpenBatterySettings -> PermissionFix.OpenBatterySettings
        else -> PermissionFix.Unavailable
    },
    fixLabelRes = R.string.permissions_open_battery,
    fixNoteRes = if (backgroundBackup != BackgroundBackupStatus.Unrestricted && !canOpenBatterySettings) {
        R.string.permissions_no_way_to_fix
    } else {
        null
    },
)
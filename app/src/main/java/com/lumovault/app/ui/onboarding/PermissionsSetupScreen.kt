package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.ui.components.CardAction
import com.lumovault.app.ui.components.PermissionCard
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.theme.SpaceSm

/**
 * Screen 4. Media access, notifications and background backup are one screen with three cards
 * (PRD section 38), because they are one decision for the user — "let LumoVault do its job" — not
 * three pages of dialogs.
 *
 * Every status shown here is read live from Android, and only two of the three are required to be
 * granted for anything: skipping an optional card changes the label, never the ability to continue.
 */
@Composable
fun PermissionsSetupScreen(
    state: OnboardingUiState,
    mediaPermanentlyDenied: Boolean,
    canOpenBatterySettings: Boolean,
    onRequestMedia: () -> Unit,
    onOpenMediaSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onSkipNotifications: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onSkipBackgroundBackup: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 4,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.setup_title),
        description = stringResource(R.string.setup_description),
        primaryLabel = stringResource(R.string.setup_action),
        onPrimary = onContinue,
        onBack = onBack,
        modifier = modifier,
    ) {
        PermissionCard(
            title = stringResource(R.string.setup_media_title),
            description = stringResource(R.string.setup_media_description),
            icon = Icons.Filled.PhotoLibrary,
            status = state.mediaAccess.label(),
            tone = state.mediaAccess.pillTone(),
            actions = {
                if (mediaPermanentlyDenied && !state.mediaAccess.allowsScanning) {
                    CardAction(
                        label = stringResource(R.string.setup_media_open_settings),
                        onClick = onOpenMediaSettings,
                    )
                } else {
                    CardAction(
                        label = stringResource(R.string.setup_media_action),
                        onClick = onRequestMedia,
                    )
                }
            },
        )

        PermissionCard(
            title = stringResource(R.string.setup_notifications_title),
            description = if (state.notifications == NotificationsStatus.NotRequired) {
                // Android below 13 has no runtime notification permission: nothing to ask, no failure.
                stringResource(R.string.setup_notifications_not_required)
            } else {
                stringResource(R.string.setup_notifications_description)
            },
            icon = Icons.Filled.NotificationsNone,
            status = state.notifications.label(),
            tone = state.notifications.pillTone(),
            actions = {
                if (!state.notifications.satisfied) {
                    CardAction(
                        label = stringResource(R.string.setup_notifications_action),
                        onClick = onRequestNotifications,
                    )
                    OutlinedButton(onClick = onSkipNotifications) {
                        Text(stringResource(R.string.setup_notifications_not_now))
                    }
                }
            },
        )

        PermissionCard(
            title = stringResource(R.string.setup_background_title),
            description = stringResource(R.string.setup_background_description),
            icon = Icons.Filled.BatteryStd,
            status = state.backgroundBackup.label(),
            tone = state.backgroundBackup.pillTone(),
            actions = {
                if (state.backgroundBackup != BackgroundBackupStatus.Unrestricted) {
                    if (canOpenBatterySettings) {
                        CardAction(
                            label = stringResource(R.string.setup_background_action),
                            onClick = onOpenBatterySettings,
                        )
                    } else {
                        // No system page to open on this device: explained rather than a dead button.
                        Text(
                            text = stringResource(R.string.setup_background_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = onSkipBackgroundBackup) {
                        Text(stringResource(R.string.setup_background_later))
                    }
                }
            },
        )
    }
}



/**
 * The media permission's answer, in the setup checklist's own words.
 *
 * Internal like [NotificationsStatus.label], and for the same reason: the Settings > Permissions screen
 * reports the same grant from the same repository, and a second `when` deciding "Allowed" is how two
 * screens started disagreeing about one permission.
 */
@Composable
internal fun MediaAccessStatus.label(): String = stringResource(
    when (this) {
        MediaAccessStatus.Granted -> R.string.status_allowed
        MediaAccessStatus.PartiallyGranted -> R.string.status_partly_allowed
        MediaAccessStatus.Denied -> R.string.status_not_set
        MediaAccessStatus.Unknown -> R.string.status_not_set
    },
)

/**
 * The permission's answer in the setup checklist's own words.
 *
 * Internal because the settings notifications screen reports the same fact from the same repository, and
 * a second `when` deciding "Allowed" would be how the two screens started disagreeing about one grant.
 */
@Composable
internal fun NotificationsStatus.label(): String = stringResource(
    when (this) {
        NotificationsStatus.Granted -> R.string.status_allowed
        NotificationsStatus.NotRequired -> R.string.status_done
        NotificationsStatus.Denied -> R.string.status_skipped
        NotificationsStatus.Unknown -> R.string.status_not_set
    },
)

/**
 * Whether the background row is satisfied, as one shared answer rather than two lists of cases.
 *
 * The words and the colour travel together on purpose: a screen that printed "Allowed" beside an
 * outline chip — or "Skipped" beside a filled one — would be reporting the same permission two ways at
 * once, which is the exact failure the setup checklist is checked for.
 */
@Composable
internal fun NotificationsStatus.pillTone(): PillTone = when (this) {
    NotificationsStatus.Granted, NotificationsStatus.NotRequired -> PillTone.Done
    NotificationsStatus.Denied -> PillTone.Skipped
    NotificationsStatus.Unknown -> PillTone.Missing
}

/** As [NotificationsStatus.pillTone], for the media grant; see it for why the two travel together. */
@Composable
internal fun MediaAccessStatus.pillTone(): PillTone = when (this) {
    MediaAccessStatus.Granted -> PillTone.Done
    // Partial access is a real grant of part of the library, so it is a tick and not a gap — the same
    // answer `OnboardingSummary` gives it, and the one the Photos screen's limited-access card shows.
    MediaAccessStatus.PartiallyGranted -> PillTone.Done
    MediaAccessStatus.Denied, MediaAccessStatus.Unknown -> PillTone.Missing
}

@Composable
internal fun BackgroundBackupStatus.label(): String = stringResource(
    when (this) {
        BackgroundBackupStatus.Unrestricted -> R.string.status_unrestricted
        BackgroundBackupStatus.Restricted -> R.string.status_restricted
        BackgroundBackupStatus.Unknown -> R.string.status_unavailable
    },
)

/**
 * Unrestricted is the answer the user was asked for, so it is a tick. Restricted is the device throttling
 * LumoVault and the user has not been asked anything about it yet, which is what an outline means;
 * Unknown is the one answer this app cannot give, which is the fill the other screens reserve for it.
 *
 * These three are the setup card's own mapping, kept exactly as it shipped — extracted so the Settings
 * copy of this row reports a device restriction in the same colour rather than inventing a second one.
 */
@Composable
internal fun BackgroundBackupStatus.pillTone(): PillTone = when (this) {
    BackgroundBackupStatus.Unrestricted -> PillTone.Done
    BackgroundBackupStatus.Restricted -> PillTone.Missing
    BackgroundBackupStatus.Unknown -> PillTone.Unavailable
}

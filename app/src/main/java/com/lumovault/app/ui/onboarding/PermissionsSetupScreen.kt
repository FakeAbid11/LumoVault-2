package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.BackgroundBackupStatus
import com.lumovault.app.ui.components.IconCircle
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
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
        SetupCard(
            title = stringResource(R.string.setup_media_title),
            description = stringResource(R.string.setup_media_description),
            icon = Icons.Filled.PhotoLibrary,
            status = state.mediaAccess.label(),
            tone = if (state.mediaAccess.allowsScanning) PillTone.Done else PillTone.Missing,
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

        SetupCard(
            title = stringResource(R.string.setup_notifications_title),
            description = if (state.notifications == NotificationsStatus.NotRequired) {
                // Android below 13 has no runtime notification permission: nothing to ask, no failure.
                stringResource(R.string.setup_notifications_not_required)
            } else {
                stringResource(R.string.setup_notifications_description)
            },
            icon = Icons.Filled.NotificationsNone,
            status = state.notifications.label(),
            tone = when {
                state.notifications.satisfied -> PillTone.Done
                state.notifications == NotificationsStatus.Denied -> PillTone.Skipped
                else -> PillTone.Missing
            },
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

        SetupCard(
            title = stringResource(R.string.setup_background_title),
            description = stringResource(R.string.setup_background_description),
            icon = Icons.Filled.BatteryStd,
            status = state.backgroundBackup.label(),
            tone = when (state.backgroundBackup) {
                BackgroundBackupStatus.Unrestricted -> PillTone.Done
                BackgroundBackupStatus.Restricted -> PillTone.Missing
                BackgroundBackupStatus.Unknown -> PillTone.Unavailable
            },
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

@Composable
private fun SetupCard(
    title: String,
    description: String,
    icon: ImageVector,
    status: String,
    tone: PillTone,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconCircle(imageVector = icon)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(text = status, tone = tone)
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(SpaceSm)) { actions() }
        }
    }
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick) { Text(label) }
}

@Composable
private fun MediaAccessStatus.label(): String = stringResource(
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

@Composable
private fun BackgroundBackupStatus.label(): String = stringResource(
    when (this) {
        BackgroundBackupStatus.Unrestricted -> R.string.status_unrestricted
        BackgroundBackupStatus.Restricted -> R.string.status_restricted
        BackgroundBackupStatus.Unknown -> R.string.status_unavailable
    },
)

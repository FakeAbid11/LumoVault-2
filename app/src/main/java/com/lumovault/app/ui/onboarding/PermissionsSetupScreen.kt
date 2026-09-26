package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.NotificationsStatus
import com.lumovault.app.domain.model.BackgroundBackupStatus

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
            status = state.mediaAccess.label(),
            satisfied = state.mediaAccess.allowsScanning,
            actions = {
                if (mediaPermanentlyDenied && !state.mediaAccess.allowsScanning) {
                    CardAction(
                        label = stringResource(R.string.setup_media_open_settings),
                        onClick = onOpenMediaSettings,
                        primary = true,
                    )
                } else {
                    CardAction(
                        label = stringResource(R.string.setup_media_action),
                        onClick = onRequestMedia,
                        primary = true,
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
            status = state.notifications.label(),
            satisfied = state.notifications.satisfied,
            actions = {
                if (!state.notifications.satisfied) {
                    CardAction(
                        label = stringResource(R.string.setup_notifications_action),
                        onClick = onRequestNotifications,
                        primary = true,
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
            status = state.backgroundBackup.label(),
            satisfied = state.backgroundBackup == BackgroundBackupStatus.Unrestricted,
            actions = {
                if (state.backgroundBackup != BackgroundBackupStatus.Unrestricted) {
                    if (canOpenBatterySettings) {
                        CardAction(
                            label = stringResource(R.string.setup_background_action),
                            onClick = onOpenBatterySettings,
                            primary = true,
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
    status: String,
    satisfied: Boolean,
    actions: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = if (satisfied) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (satisfied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@Composable
private fun CardAction(label: String, onClick: () -> Unit, primary: Boolean) {
    if (primary) {
        FilledTonalButton(onClick = onClick) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick) { Text(label) }
    }
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

@Composable
private fun NotificationsStatus.label(): String = stringResource(
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

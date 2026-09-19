package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R

/**
 * Notification settings — one toggle per notification type.
 *
 * Ported from notification_settings_screen.dart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.notifications_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.notifications_section_backup) }
            item {
                SettingsSwitchItem(
                    title = R.string.notifications_backup_progress,
                    subtitle = R.string.notifications_backup_progress_subtitle,
                    checked = settings.backupProgressNotification,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(backupProgressNotification = v) }
                    },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.notifications_backup_completed,
                    subtitle = R.string.notifications_backup_completed_subtitle,
                    checked = settings.backupCompletedNotification,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(backupCompletedNotification = v) }
                    },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.notifications_backup_failed,
                    subtitle = R.string.notifications_backup_failed_subtitle,
                    checked = settings.backupFailedNotification,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(backupFailedNotification = v) }
                    },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.notifications_section_restore) }
            item {
                SettingsSwitchItem(
                    title = R.string.notifications_restore_completed,
                    subtitle = R.string.notifications_restore_completed_subtitle,
                    checked = settings.restoreCompletedNotification,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(restoreCompletedNotification = v) }
                    },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.notifications_section_system) }
            item {
                SettingsSwitchItem(
                    title = R.string.notifications_storage_warning,
                    subtitle = R.string.notifications_storage_warning_subtitle,
                    checked = settings.storageWarningNotification,
                    onCheckedChange = { v ->
                        viewModel.update { it.copy(storageWarningNotification = v) }
                    },
                )
            }
        }
    }
}

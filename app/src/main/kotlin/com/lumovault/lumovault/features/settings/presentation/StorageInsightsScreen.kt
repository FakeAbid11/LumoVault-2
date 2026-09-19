package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import java.text.DateFormat
import java.util.Date

/**
 * Storage insights — static version.
 *
 * Ported from storage_insights_screen.dart with all live engine data removed:
 * the original's donut chart, per-folder breakdown and backup-health sections
 * read the backup engine and storage-usage provider, none of which exist in
 * this rewrite yet. What remains is honest: what backup includes and when it
 * last ran, all from persisted settings. No DB queries are invented here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageInsightsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.insights_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.insights_section_backup) }
            item {
                InsightsRow(
                    title = R.string.insights_backup_photos,
                    value = yesNo(settings.backupPhotos),
                    icon = Icons.Default.Photo,
                )
            }
            item {
                InsightsRow(
                    title = R.string.insights_backup_videos,
                    value = yesNo(settings.backupVideos),
                    icon = Icons.Default.Videocam,
                )
            }
            item {
                InsightsRow(
                    title = R.string.insights_last_backup,
                    value = formatTimestamp(settings.lastBackupAt),
                    icon = Icons.Default.Cloud,
                )
            }
            item {
                InsightsRow(
                    title = R.string.insights_last_scan,
                    value = formatTimestamp(settings.lastScanAt),
                    icon = Icons.Default.PhotoLibrary,
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.insights_storage_channel) }
            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.insights_storage_channel)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                if (settings.storageChannelId != null) {
                                    R.string.insights_channel_ready
                                } else {
                                    R.string.insights_channel_missing
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun InsightsRow(
    title: Int,
    value: String,
    icon: ImageVector,
) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(value) },
        leadingContent = { Icon(icon, contentDescription = null) },
    )
}

@Composable
private fun yesNo(value: Boolean): String = stringResource(
    if (value) R.string.insights_value_yes else R.string.insights_value_no,
)

@Composable
private fun formatTimestamp(epochMillis: Long?): String =
    if (epochMillis == null) {
        stringResource(R.string.insights_never)
    } else {
        DateFormat.getDateTimeInstance().format(Date(epochMillis))
    }

package com.lumovault.lumovault.features.backup.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import kotlin.math.roundToInt

/**
 * Backup settings — every backup-related toggle in [AppSettings].
 *
 * Ported from backup_settings_screen_v2.dart. This screen is fully functional:
 * it is pure settings, so every control writes straight through
 * [BackupViewModel.updateSettings] (i.e. `SettingsRepository.update`), exactly
 * like the original's `backupSettingsProvider.notifier.update*`. The engine-
 * dependent bits of the original (live status tile, pause action, MIUI banner)
 * are omitted — no engine exists in this build.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item { SectionHeader(stringResource(R.string.backup_section_schedule)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.backup_auto_backup),
                    subtitle = stringResource(R.string.backup_auto_backup_subtitle),
                    checked = settings.autoBackupEnabled,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(autoBackupEnabled = it) }
                    },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.backup_wifi_only),
                    subtitle = stringResource(R.string.backup_wifi_only_subtitle),
                    checked = settings.wifiOnly,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(wifiOnly = it) }
                    },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.backup_charging_only),
                    subtitle = stringResource(R.string.backup_charging_only_subtitle),
                    checked = settings.chargingOnly,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(chargingOnly = it) }
                    },
                )
            }
            item {
                BatterySlider(
                    level = settings.minBatteryLevel,
                    onLevelChange = {
                        viewModel.updateSettings { s -> s.copy(minBatteryLevel = it) }
                    },
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.backup_section_media)) }
            item {
                SwitchRow(
                    title = stringResource(R.string.backup_photos),
                    subtitle = null,
                    checked = settings.backupPhotos,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(backupPhotos = it) }
                    },
                )
            }
            item {
                SwitchRow(
                    title = stringResource(R.string.backup_videos),
                    subtitle = null,
                    checked = settings.backupVideos,
                    onCheckedChange = {
                        viewModel.updateSettings { s -> s.copy(backupVideos = it) }
                    },
                )
            }
            item { HorizontalDivider() }

            item { SectionHeader(stringResource(R.string.backup_section_performance)) }
            item {
                ChoiceRow(
                    title = stringResource(R.string.backup_max_parallel),
                    value = stringResource(
                        R.string.backup_max_parallel_value,
                        settings.maxParallelUploads,
                    ),
                    options = listOf(1, 2, 3, 4, 5),
                    optionLabel = {
                        stringResource(R.string.backup_max_parallel_value, it)
                    },
                    onSelect = {
                        viewModel.updateSettings { s -> s.copy(maxParallelUploads = it) }
                    },
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.backup_batch_size),
                    value = stringResource(
                        R.string.backup_batch_size_value,
                        settings.uploadBatchSize,
                    ),
                    options = listOf(5, 10, 20, 50),
                    optionLabel = {
                        stringResource(R.string.backup_batch_size_value, it)
                    },
                    onSelect = {
                        viewModel.updateSettings { s -> s.copy(uploadBatchSize = it) }
                    },
                )
            }
            item {
                ChoiceRow(
                    title = stringResource(R.string.backup_upload_delay),
                    value = formatDelayMs(settings.uploadDelayMs),
                    options = listOf(500, 1_000, 2_000, 5_000),
                    optionLabel = { formatDelayMs(it) },
                    onSelect = {
                        viewModel.updateSettings { s -> s.copy(uploadDelayMs = it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/**
 * Min-battery slider. Writes on drag-stop via the confirmation callback so a
 * slow drag doesn't spam [SettingsRepository] writes; the label follows the
 * in-progress value.
 */
@Composable
private fun BatterySlider(level: Int, onLevelChange: (Int) -> Unit) {
    var dragging by remember { mutableIntStateOf(level) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.backup_min_battery),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.backup_min_battery_value, dragging),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = dragging.toFloat(),
            onValueChange = { dragging = it.roundToInt() },
            onValueChangeFinished = { onLevelChange(dragging) },
            valueRange = 0f..50f,
            steps = 9,
        )
    }
}

/**
 * Current-value row opening a radio-choice dialog — the Compose equivalent of
 * the original's `SimpleDialog` + options list.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value) },
        modifier = Modifier.clickable { showDialog = true },
    )
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { option ->
                        TextButton(onClick = {
                            onSelect(option)
                            showDialog = false
                        }) {
                            Text(optionLabel(option))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** 500ms / 1 second / 2 seconds / 5 seconds, as in the original dialog. */
private fun formatDelayMs(ms: Int): String =
    if (ms < 1_000) "${ms}ms" else "${ms / 1_000} second" + if (ms >= 2_000) "s" else ""


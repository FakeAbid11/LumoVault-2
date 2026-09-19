package com.lumovault.lumovault.features.backup.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Backup dashboard — status card, per-status counts, last-backup time.
 *
 * Ported from backup_dashboard_screen.dart (PRD 8.3). The upload engine is
 * not part of this build, so pause/resume/retry and the live queue list are
 * omitted and Start is rendered *disabled* with an explanatory caption —
 * a control that looks finished but does nothing is worse than a disabled
 * one. Telegram sign-in state is likewise unknown, so instead of a badge
 * there is a hint row navigating to [onConnectTelegram].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupDashboardScreen(
    onBack: () -> Unit,
    onNavigate: (String) -> Unit,
    onConnectTelegram: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_dashboard_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onNavigate("backup/settings") }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = stringResource(R.string.backup_open_settings),
                        )
                    }
                },
            )
        },
    ) { padding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            // Connect-Telegram hint row: sign-in state is unknown in this
            // build, so this is an honest pointer, not a status badge.
            Card(onClick = onConnectTelegram, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                    Text(
                        stringResource(R.string.backup_connect_telegram_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status card: counts from Room, last backup from settings.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.backup_status_card_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        CountChip(
                            label = stringResource(R.string.backup_count_pending),
                            value = counts.pending,
                        )
                        CountChip(
                            label = stringResource(R.string.backup_count_uploaded),
                            value = counts.uploaded,
                        )
                        CountChip(
                            label = stringResource(R.string.backup_count_failed),
                            value = counts.failed,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        settings.lastBackupAt
                            ?.let {
                                stringResource(
                                    R.string.backup_last_backup,
                                    formatBackupTimestamp(it),
                                )
                            }
                            ?: stringResource(R.string.backup_last_backup_never),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // The engine isn't in this build: disabled button + caption, per
            // the rewrite's honesty rule.
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.backup_start))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                stringResource(R.string.backup_engine_unavailable_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CountChip(label: String, value: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            "$value",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Matches the original `_formatDateTime`: yyyy-MM-dd HH:mm in local time. */
private fun formatBackupTimestamp(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.ui.formatBytes

/**
 * Storage usage — per-status counts and byte totals from Room.
 *
 * Ported from storage_stats_screen.dart. The original's headline was bytes
 * backed up to Telegram plus item counts; same here, all sourced from
 * [BackupViewModel.counts] (a `groupBy` over the timeline flow — no new DAO
 * queries). The pull-to-refresh invalidate of the original is unnecessary:
 * the Room flow is already live.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageStatsScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel(),
) {
    val counts by viewModel.counts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.storage_stats_title)) },
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        formatBytes(counts.backedUpBytes),
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (counts.uploaded > 0) {
                            stringResource(
                                R.string.storage_stats_backed_up_items,
                                counts.uploaded,
                                counts.total,
                            )
                        } else {
                            stringResource(R.string.storage_stats_none_backed_up)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(
                            R.string.storage_stats_total_bytes,
                            formatBytes(counts.totalBytes),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(R.string.storage_stats_total),
                    value = "${counts.total}",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = stringResource(R.string.storage_stats_backed_up),
                    value = "${counts.uploaded}",
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(R.string.storage_stats_pending),
                    value = "${counts.pending}",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = stringResource(R.string.storage_stats_failed),
                    value = "${counts.failed}",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

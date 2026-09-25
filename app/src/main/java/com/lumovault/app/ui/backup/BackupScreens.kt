package com.lumovault.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.domain.model.BackupHealth
import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.util.toByteText
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The Phase 9 screens: the backup settings that drive the real scheduler, the health summary, the technical
 * panel, and Free Up Space.
 *
 * They are grouped in one file because they share their vocabulary — a labelled row, a byte figure, a
 * timestamp — and because none of them is allowed to decide anything on its own: every number comes from a
 * repository aggregate and every action is one call to a use case that already exists.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupHubScreen(
    onNavigateUp: () -> Unit,
    onOpenFreeUpSpace: () -> Unit,
    onOpenHealth: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_hub_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_hub_section_backup),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.backup_automatic),
                    subtitle = stringResource(R.string.backup_automatic_note),
                    checked = preferences.automatic,
                    onChange = viewModel::setAutomatic,
                )
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.backup_wifi_only),
                    subtitle = stringResource(R.string.backup_wifi_only_note),
                    checked = preferences.wifiOnly,
                    enabled = preferences.automatic,
                    onChange = viewModel::setWifiOnly,
                )
            }
            item {
                ToggleRow(
                    title = stringResource(R.string.backup_charging_only),
                    subtitle = stringResource(R.string.backup_charging_only_note),
                    checked = preferences.chargingOnly,
                    enabled = preferences.automatic,
                    onChange = viewModel::setChargingOnly,
                )
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Text(
                    text = stringResource(R.string.backup_hub_section_status),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item { HealthSummary(health = health, onOpenDetails = onOpenHealth) }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp)) }
            item {
                Text(
                    text = stringResource(R.string.backup_hub_section_storage),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.free_space_title),
                    subtitle = stringResource(R.string.free_space_entry_note),
                    onClick = onOpenFreeUpSpace,
                )
            }
            item {
                EntryRow(
                    title = stringResource(R.string.diagnostics_title),
                    subtitle = stringResource(R.string.diagnostics_entry_note),
                    onClick = onOpenDiagnostics,
                )
            }
        }
    }
}

@Composable
private fun HealthSummary(health: BackupHealth, onOpenDetails: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = if (health.allLocalMediaBackedUp) {
                    stringResource(R.string.health_all_backed_up, health.backedUp.toString())
                } else {
                    stringResource(R.string.health_backed_up, health.backedUp.toString(), health.localTotal.toString())
                },
                style = MaterialTheme.typography.titleMedium,
            )
            CountLine(R.string.health_waiting, health.pending)
            CountLine(R.string.health_failed, health.failed)
            CountLine(R.string.health_cloud_only, health.cloudOnly)
            Text(
                text = stringResource(
                    R.string.health_last_backup,
                    health.lastBackupSeconds?.asText() ?: stringResource(R.string.health_never),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = stringResource(
                    R.string.health_last_scan,
                    health.lastScanSeconds?.asText() ?: stringResource(R.string.health_never),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            if (health.notBackedUp > 0) {
                Text(
                    text = stringResource(R.string.health_not_backed_up, health.notBackedUp.toString()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            TextButton(onClick = onOpenDetails) {
                Text(stringResource(R.string.health_view_issues))
            }
        }
    }
}

@Composable
private fun CountLine(@androidx.annotation.StringRes label: Int, count: Int) {
    if (count <= 0) return
    Text(
        text = stringResource(label, count.toString()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The same aggregate, laid out on its own screen with the two things a summary card has no room for: how
 * much is waiting to be freed, and the fact that an item in this library has no proven cloud copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupHealthScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val health by viewModel.health.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HealthSummary(health = health, onOpenDetails = onNavigateUp)
            if (health.reclaimableCount > 0) {
                Text(
                    text = stringResource(
                        R.string.health_reclaimable,
                        health.reclaimableCount.toString(),
                        health.reclaimableBytes.toByteText(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (health.neverScanned) {
                Text(
                    text = stringResource(R.string.health_no_scan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (health.neverBackedUp) {
                Text(
                    text = stringResource(R.string.health_no_backup),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val current = diagnostics

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item { Row(R.string.diag_local_media, current.health.localTotal.toString()) }
            item { Row(R.string.diag_backed_up, current.health.backedUp.toString()) }
            item { Row(R.string.diag_cloud_only, current.health.cloudOnly.toString()) }
            item { Row(R.string.diag_waiting, current.health.waiting.toString()) }
            item { Row(R.string.diag_uploading, current.health.uploading.toString()) }
            item { Row(R.string.diag_failed, current.health.failed.toString()) }
            item {
                Row(
                    R.string.diag_last_backup,
                    current.health.lastBackupSeconds?.asText() ?: stringResource(R.string.health_never),
                )
            }
            item {
                Row(
                    R.string.diag_last_scan,
                    current.health.lastScanSeconds?.asText() ?: stringResource(R.string.health_never),
                )
            }
            item {
                Row(
                    R.string.diag_telegram,
                    stringResource(
                        when (current.telegram) {
                            TelegramWord.Connected -> R.string.diag_telegram_connected
                            TelegramWord.WaitingForSignIn -> R.string.diag_telegram_waiting
                            TelegramWord.NotConfigured -> R.string.diag_telegram_not_configured
                            TelegramWord.Unavailable -> R.string.diag_telegram_unavailable
                        },
                    ),
                )
            }
            item {
                Row(
                    R.string.diag_channel,
                    stringResource(
                        if (current.channelAvailable) R.string.diag_channel_available else R.string.diag_channel_missing,
                    ),
                )
            }
            item {
                Row(
                    R.string.diag_automatic,
                    stringResource(
                        if (current.preferences.automatic) R.string.state_enabled else R.string.state_disabled,
                    ),
                )
            }
            item {
                Row(
                    R.string.diag_constraints,
                    stringResource(current.preferences.constraintsSummaryRes()),
                )
            }
            item { Row(R.string.diag_database_version, current.databaseVersion.toString()) }
            item { Row(R.string.diag_staging_space, current.stagingSpaceFreeBytes.toByteText()) }
            item {
                Text(
                    text = stringResource(R.string.diagnostics_secrets_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Row(@androidx.annotation.StringRes label: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

private fun BackupPreferences.constraintsSummaryRes(): Int = when {
    wifiOnly && chargingOnly -> R.string.diag_constraints_wifi_charging
    wifiOnly -> R.string.diag_constraints_wifi
    chargingOnly -> R.string.diag_constraints_charging
    else -> R.string.diag_constraints_any
}

private fun Long.asText(): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
    .format(Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault()))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FreeUpSpaceScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FreeUpSpaceViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val consent by viewModel.pendingConsent.collectAsStateWithLifecycle()
    var confirmShown by remember { mutableStateOf(false) }
    val outcomeText = when (val outcome = state.outcome) {
        DeletionOutcome.None, DeletionOutcome.Asked -> null
        is DeletionOutcome.Removed ->
            stringResource(R.string.free_space_done, outcome.deleted.toString(), outcome.reclaimedBytes.toByteText())
        DeletionOutcome.NothingLeft -> stringResource(R.string.free_space_nothing_left)
        DeletionOutcome.Declined -> stringResource(R.string.free_space_declined)
        DeletionOutcome.Unavailable -> stringResource(R.string.free_space_unavailable)
        DeletionOutcome.Failed -> stringResource(R.string.free_space_failed)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.consentConsumed()
        if (result.resultCode == android.app.Activity.RESULT_OK) viewModel.onDeleted() else viewModel.onDeclined()
    }

    consent?.let { sender ->
        LaunchedEffect(sender) {
            launcher.launch(
                IntentSenderRequest.Builder(sender)
                    .setFillInIntent(null)
                    .setFlags(0, 0)
                    .build(),
            )
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.free_space_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.candidates.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.free_space_selected,
                            state.selected.size.toString(),
                            state.selectedBytes.toByteText(),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = { confirmShown = true },
                        enabled = state.selected.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.free_space_action))
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    text = when {
                        state.nothingEligible -> stringResource(R.string.free_space_none)
                        else -> stringResource(
                            R.string.free_space_header,
                            state.plan.reclaimableBytes.toByteText(),
                            state.plan.eligibleCount.toString(),
                        )
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.free_space_remains),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (outcomeText != null) {
                item {
                    OutcomeCard(text = outcomeText, onDismiss = viewModel::dismissOutcome)
                }
            }

            if (state.candidates.isEmpty() && !state.nothingEligible) {
                item { CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp)) }
            }

            items(state.candidates, key = { it.mediaStoreId }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    checked = candidate.mediaStoreId in state.selected,
                    onToggle = { viewModel.toggle(candidate) },
                )
            }

            item {
                OutlinedButton(onClick = viewModel::refresh, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.free_space_review_refresh))
                }
            }
        }
    }

    if (confirmShown) {
        AlertDialog(
            onDismissRequest = { confirmShown = false },
            title = { Text(stringResource(R.string.free_space_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.free_space_confirm_body,
                        state.selected.size.toString(),
                        state.selectedBytes.toByteText(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmShown = false
                        viewModel.confirm()
                    },
                ) {
                    Text(stringResource(R.string.free_space_confirm_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmShown = false }) { Text(stringResource(R.string.backup_cancel)) }
            },
        )
    }
}

@Composable
private fun OutcomeCard(text: String, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

@Composable
private fun CandidateRow(candidate: FreeUpSpaceCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(modifier = Modifier.weight(1f)) {
            Text(text = candidate.displayName, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = candidate.sizeBytes.toByteText(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.Filled.Check, contentDescription = null)
    }
}

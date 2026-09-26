package com.lumovault.app.ui.backup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.clip
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
import com.lumovault.app.ui.theme.GroupCardCorner
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MinTouchTarget
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs
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
    onOpenFolders: () -> Unit,
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = SpaceLg,
                end = SpaceLg,
                top = SpaceXs,
                bottom = SpaceXl,
            ),
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            item { SectionLabel(R.string.backup_hub_section_backup) }
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

            // The source lives here as well as in setup, because the answer changes: a folder the camera
            // created last week cannot be chosen without opening onboarding again otherwise, and this is
            // the screen a person is already on.
            item {
                val line = viewModel.source.collectAsStateWithLifecycle().value
                EntryRow(
                    title = stringResource(R.string.backup_folders_title),
                    subtitle = line.label(),
                    onClick = onOpenFolders,
                )
            }

            // The rule between sections is gone: a heading that is a heading does the separating, and two
            // hairlines plus their margins were 24 dp of decoration saying what four words already said.
            item { SectionLabel(R.string.backup_hub_section_status) }
            item { HealthSummary(health = health, onOpenDetails = onOpenHealth) }

            item { SectionLabel(R.string.backup_hub_section_storage) }
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

/** A section of the hub: a heading that separates, with no rule to do it for it. */
@Composable
private fun SectionLabel(@androidx.annotation.StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = LumoVaultType.sectionHeader,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = SpaceMd, bottom = SpaceXs),
    )
}

/**
 * The one card on this screen, and deliberately the compact one.
 *
 * It used to run to eight lines — a title, three counts, two timestamps, a warning and a button — which made
 * the summary taller than the settings above it and turned the screen into a report about the queue rather
 * than a place to decide things. The counts are one line and the timestamps are one line: each figure is the
 * same string from the same resource, and a person scanning "3 waiting · 1 failed" is reading the same three
 * facts they read from three rows, in a third of the height. A zero count is still absent rather than shown,
 * because "0 failed" is not news and the line that carries it is.
 */
@Composable
private fun HealthSummary(health: BackupHealth, onOpenDetails: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(GroupCardCorner),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Text(
                text = if (health.allLocalMediaBackedUp) {
                    stringResource(R.string.health_all_backed_up, health.backedUp.toString())
                } else {
                    stringResource(R.string.health_backed_up, health.backedUp.toString(), health.localTotal.toString())
                },
                style = LumoVaultType.itemTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val counts = listOfNotNull(
                if (health.pending > 0) stringResource(R.string.health_waiting, health.pending.toString()) else null,
                if (health.failed > 0) stringResource(R.string.health_failed, health.failed.toString()) else null,
                if (health.cloudOnly > 0) {
                    stringResource(R.string.health_cloud_only, health.cloudOnly.toString())
                } else {
                    null
                },
            )
            if (counts.isNotEmpty()) {
                Text(
                    text = counts.joinToString(separator = "  ·  "),
                    style = LumoVaultType.sectionDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Text(
                text = stringResource(
                    R.string.health_last_backup,
                    health.lastBackupSeconds?.asText() ?: stringResource(R.string.health_never),
                ) + "  ·  " + stringResource(
                    R.string.health_last_scan,
                    health.lastScanSeconds?.asText() ?: stringResource(R.string.health_never),
                ),
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // The one line this screen is allowed to shout with, and it does: an item that has no cloud copy
            // is the fact the whole feature exists to fix. Everything else here is quiet so that this is not
            // one red row in a screen of warnings.
            if (health.notBackedUp > 0) {
                Text(
                    text = stringResource(R.string.health_not_backed_up, health.notBackedUp.toString()),
                    style = LumoVaultType.sectionDetail,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TextButton(
                onClick = onOpenDetails,
                modifier = Modifier.align(Alignment.Start),
            ) {
                Text(stringResource(R.string.health_view_issues))
            }
        }
    }
}

/**
 * The same aggregate, laid out on its own screen with the two things a summary card has no room for: how
 * much is waiting to be freed, and the fact that an item in this library has no proven cloud copy.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupHealthScreen(
    onNavigateUp: () -> Unit,
    /** The counts on this screen are only half an answer; the other half is which items and why. */
    onOpenDiagnostics: () -> Unit,
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
            HealthSummary(health = health, onOpenDetails = onOpenDiagnostics)
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
            val current = diagnostics
            if (current == null) {
                // Every row below is a fact about the device, and none of it has been read yet. Zeros would
                // read as a database at version 0 and a full disk, which is inventing both.
                item { CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp)) }
                return@LazyColumn
            }
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

internal fun BackupPreferences.constraintsSummaryRes(): Int = when {
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
        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> viewModel.onDeleted()

            // The user backing out of Android's own dialog is the only cancellation that is a choice.
            // Every other code — including `RESULT_CANCELED`'s neighbours, which is what the framework
            // returns when it refuses the request outright — is the device saying no, and reporting that
            // as "you declined" tells the person something happened they did not do.
            android.app.Activity.RESULT_CANCELED -> viewModel.onDeclined()

            else -> viewModel.onFailure()
        }
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
                        // Preparing hashes files and asks the resolver, which takes seconds on a large
                        // selection: a button that stays live through it starts the work twice.
                        enabled = state.selected.isNotEmpty() && !state.confirming,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.confirming) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text(stringResource(R.string.free_space_action))
                        }
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

            // Three cases, not two. A review that is still running and a review that came back with
            // nothing are different facts, and drawing a spinner for both leaves the screen promising an
            // answer that never arrives while its own header quotes real totals over zero rows.
            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(top = 24.dp)) }
            } else if (state.candidates.isEmpty() && !state.nothingEligible) {
                item {
                    Text(
                        text = stringResource(R.string.free_space_review_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.candidates.isNotEmpty()) {
                // A review can list four hundred rows, and ticking them one at a time is not how a person
                // empties a phone. The row is the list's own control, not a mode the screen can get stuck in.
                item {
                    TextButton(
                        onClick = {
                            if (state.allSelected) viewModel.clearSelection() else viewModel.selectAll()
                        },
                    ) {
                        Text(
                            stringResource(
                                if (state.allSelected) R.string.free_space_clear_selection
                                else R.string.free_space_select_all,
                            ),
                        )
                    }
                }
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
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .padding(vertical = SpaceXs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LumoVaultType.itemTitle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/**
 * A row that opens another screen.
 *
 * The mark at its end used to be a tick. On the folders row that could be read as "these are chosen", but the
 * same component draws "Free up space" and "Diagnostics", and a tick on those says nothing true — the screen
 * they open is not a state this one can confirm. A chevron says the one thing that is always true about an
 * entry row: there is somewhere to go.
 */
@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(GroupCardCorner))
            .clickable(onClick = onClick)
            .padding(horizontal = SpaceXs, vertical = SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = LumoVaultType.itemTitle, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

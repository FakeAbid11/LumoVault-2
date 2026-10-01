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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.app.R
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.domain.backup.BackupFailureItem
import com.lumovault.app.domain.model.BackupHealth
import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.onboarding.onboardingBackdrop
import com.lumovault.app.ui.screens.photos.backupFailureReasonRes
import com.lumovault.app.util.toByteText
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MarkInline
import com.lumovault.app.ui.theme.MinTouchTarget
import com.lumovault.app.ui.theme.RingStroke
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SpaceXxs
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
    /** The Cloud tab, which is where a backup channel is adopted — the queue now does it too, and this is the door. */
    onOpenCloudTab: () -> Unit,
    onConnectTelegram: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val health by viewModel.health.collectAsStateWithLifecycle()
    val preferences by viewModel.preferences.collectAsStateWithLifecycle()
    val stop by viewModel.stopReason.collectAsStateWithLifecycle()

    // The setup flow's frame, extended: the app's settings half sits on the same brand gradient, and its
    // own bar draws nothing over it — a surface-coloured stripe across a tinted body is exactly what
    // OnboardingScaffold's comment says not to do. The tabs keep the shell's opaque bar: the gradient is
    // where the app configures itself, not where it shows photographs.
    Scaffold(
        modifier = modifier.fillMaxSize().background(onboardingBackdrop()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_hub_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
                // A switch that cannot start a pass is not a switch: with nothing chosen to back up, the
                // honest control is the door to the folders, while somebody with it already on can still
                // turn it off.
                val sourceLine = viewModel.source.collectAsStateWithLifecycle().value
                if (sourceLine.canEnableAutomatic || preferences.automatic) {
                    ToggleRow(
                        title = stringResource(R.string.backup_automatic),
                        subtitle = stringResource(R.string.backup_automatic_note),
                        checked = preferences.automatic,
                        onChange = viewModel::setAutomatic,
                    )
                } else {
                    EntryRow(
                        title = stringResource(R.string.backup_automatic),
                        subtitle = stringResource(R.string.backup_automatic_choose_first),
                        onClick = onOpenFolders,
                    )
                }
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

            // Below the numbers rather than above them: the figures are what the user came to read, and this
            // is the sentence that explains them when they do not add up.
            if (stop != null) {
                val reason = requireNotNull(stop)
                item { StopCard(reason = reason, onOpenCloudTab = onOpenCloudTab, onConnectTelegram = onConnectTelegram) }
            }

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
        // onSurface, like every other section heading in the app: this tint was the one screen that
        // muted it, and a heading that changes weight by room reads as a different kind of heading.
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = SpaceMd, bottom = SpaceXs),
    )
}

/**
 * The one card on this screen, and deliberately the compact one.
 *
 * It used to run to eight lines — a title, three counts, two timestamps, a warning and a button — which made
 * the summary taller than the settings above it and turned the screen into a report about the queue rather
 * than a place to decide things. The counts are one row of chips and the timestamps are one line: each
 * figure is the same string from the same resource, and a person scanning "3 waiting" next to "1 failed" is
 * reading the same three facts they read from three rows, in a third of the height. A zero count is still
 * absent rather than shown, because "0 failed" is not news and the line that carries it is — and each count
 * now carries its tone as well as its number, because waiting, cloud-only and failed are three different
 * kinds of news that one dot-separated sentence made look alike.
 */
@Composable
private fun HealthSummary(health: BackupHealth, onOpenDetails: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = SpaceLg, vertical = SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Text(
                text = if (health.allLocalMediaBackedUp) {
                    pluralStringResource(R.plurals.health_all_backed_up, health.backedUp, health.backedUp)
                } else {
                    stringResource(R.string.health_backed_up, health.backedUp.toString(), health.localTotal.toString())
                },
                style = LumoVaultType.itemTitle,
                color = MaterialTheme.colorScheme.onSurface,
            )

            val counts = listOfNotNull(
                if (health.pending > 0) {
                    stringResource(R.string.health_waiting, health.pending.toString()) to PillTone.Neutral
                } else {
                    null
                },
                if (health.failed > 0) {
                    stringResource(R.string.health_failed, health.failed.toString()) to PillTone.Unavailable
                } else {
                    null
                },
                if (health.cloudOnly > 0) {
                    stringResource(R.string.health_cloud_only, health.cloudOnly.toString()) to PillTone.Neutral
                } else {
                    null
                },
            )
            if (counts.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(SpaceSm)) {
                    counts.forEach { (text, tone) -> StatusPill(text = text, tone = tone) }
                }
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
 * The one thing a stalled queue was never allowed to say: which refusal it stopped on, and the door that
 * clears it.
 *
 * It appears only while work is waiting and the pass cannot proceed — see [backupStopReason] — so a library
 * that is genuinely up to date is not greeted by a red card, and a queue that has been waiting for an hour
 * is not left to look like one that is merely slow.
 */
@Composable
private fun StopCard(reason: BackupStop, onOpenCloudTab: () -> Unit, onConnectTelegram: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            // Three of these reasons are things the user chose on this very screen. Painting a queue that is
            // waiting when it was told to wait in the same red as one that is signed out claims something
            // broke, which is the one thing this card is not allowed to say about a preference.
            containerColor = if (reason.urgent) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            contentColor = if (reason.urgent) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Text(
                text = stringResource(reason.titleRes),
                style = LumoVaultType.itemTitle,
            )
            Text(
                text = stringResource(reason.bodyRes),
                style = LumoVaultType.sectionDetail,
            )
            val action: Pair<String, () -> Unit>? = when (reason) {
                BackupStop.NoChannel -> stringResource(R.string.backup_blocked_no_channel_action) to onOpenCloudTab
                BackupStop.SignedOut -> stringResource(R.string.cloud_connect_action) to onConnectTelegram
                // Nothing to open: this build was compiled without Telegram, which is a fact about the
                // artifact rather than a step the user forgot.
                BackupStop.BuildHasNoTelegram -> null
                // Nothing to open here either, and for the opposite reason: the queue needs no fixing, only
                // the network or the current the user told it to wait for. A button would offer to undo a
                // choice made one row above this card.
                BackupStop.WaitingForWifi,
                BackupStop.WaitingForCharger,
                BackupStop.WaitingForWifiAndCharger,
                -> null
            }
            if (action != null) {
                TextButton(onClick = action.second, modifier = Modifier.align(Alignment.Start)) {
                    Text(action.first)
                }
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
        modifier = modifier.fillMaxSize().background(onboardingBackdrop()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.health_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceSm),
        ) {
            HealthSummary(health = health, onOpenDetails = onOpenDiagnostics)
            if (health.reclaimableCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.health_reclaimable,
                        health.reclaimableCount,
                        health.reclaimableCount,
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
    /** The numbers above are the size of the problem; a failure is only answerable by looking at the item. */
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupViewModel = viewModel(),
) {
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val failures by viewModel.failedItems.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier.fillMaxSize().background(onboardingBackdrop()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceXxs),
        ) {
            val current = diagnostics
            if (current == null) {
                // Every row below is a fact about the device, and none of it has been read yet. Zeros would
                // read as a database at version 0 and a full disk, which is inventing both.
                item { CircularProgressIndicator(modifier = Modifier.padding(top = SpaceXl)) }
                return@LazyColumn
            }
            item { Row(R.string.diag_local_media, current.health.localTotal.toString()) }
            item { Row(R.string.diag_backed_up, current.health.backedUp.toString()) }
            item { Row(R.string.diag_cloud_only, current.health.cloudOnly.toString()) }
            item { Row(R.string.diag_waiting, current.health.waiting.toString()) }
            item { Row(R.string.diag_uploading, current.health.uploading.toString()) }
            item { Row(R.string.diag_failed, current.health.failed.toString()) }
            // The count and this list come from the same table through two queries, so the list says plainly
            // when it is only part of the story: a short list that reads as complete is the worse of the two
            // failures here.
            if (failures.isNotEmpty()) {
                item { SectionLabel(R.string.backup_failure_section) }
                items(failures, key = { failure -> failure.mediaStoreId }) { failure ->
                    FailureRow(failure = failure, onOpen = { onOpenMedia(failure.mediaStoreId) })
                }
                if (current.health.failed > failures.size) {
                    item {
                        Text(
                            text = pluralStringResource(
                                R.plurals.backup_failure_truncated,
                                failures.size,
                                failures.size,
                            ),
                            style = LumoVaultType.sectionDetail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = SpaceSm),
                        )
                    }
                }
            }
            item {
                Row(
                    R.string.diag_last_backup,
                    current.health.lastBackupSeconds?.asText() ?: stringResource(R.string.health_never),
                    // "Not yet" is a state wearing a value's clothes: the outline chip is the same mark the
                    // checklist uses for a step that has nothing to show. A real timestamp stays plain text,
                    // because a time is data about a state rather than the state itself.
                    tone = if (current.health.lastBackupSeconds == null) PillTone.Missing else null,
                )
            }
            item {
                Row(
                    R.string.diag_last_scan,
                    current.health.lastScanSeconds?.asText() ?: stringResource(R.string.health_never),
                    tone = if (current.health.lastScanSeconds == null) PillTone.Missing else null,
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
                    // The same four answers the setup checklist gives, in its colours: connected is Done,
                    // waiting on the user is quiet, not-configured is an absence (outline, never red —
                    // nobody has been asked to sign in yet), and unavailable is the only one that means
                    // something is actually wrong.
                    tone = when (current.telegram) {
                        TelegramWord.Connected -> PillTone.Done
                        TelegramWord.WaitingForSignIn -> PillTone.Neutral
                        TelegramWord.NotConfigured -> PillTone.Missing
                        TelegramWord.Unavailable -> PillTone.Unavailable
                    },
                )
            }
            item {
                Row(
                    R.string.diag_channel,
                    stringResource(
                        if (current.channelAvailable) R.string.diag_channel_available else R.string.diag_channel_missing,
                    ),
                    tone = if (current.channelAvailable) PillTone.Done else PillTone.Missing,
                )
            }
            item {
                Row(
                    R.string.diag_automatic,
                    stringResource(
                        if (current.preferences.automatic) R.string.state_enabled else R.string.state_disabled,
                    ),
                    // A setting the user switched off is a choice, not a fault, and StopCard's own comment
                    // says the same about preferences: Disabled wears the quiet fill, and only Missing's
                    // outline marks something the app was never told or can no longer find.
                    tone = if (current.preferences.automatic) PillTone.Done else PillTone.Skipped,
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
                    modifier = Modifier.padding(top = SpaceMd),
                )
            }
        }
    }
}

/**
 * One line of the technical panel: the label, and the value in the form it should be read in.
 *
 * A value that *is* a state — Connected, Available, Enabled, "not yet" — earns a [StatusPill], so the words
 * that say how things stand are the same words in the same colours as every other screen's. Figures,
 * timestamps and constraints stay plain text: they are data about a state rather than the state itself, and
 * a chip around a number only makes the row harder to scan.
 */
@Composable
private fun Row(@androidx.annotation.StringRes label: Int, value: String, tone: PillTone? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = SpaceSm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (tone == null) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        } else {
            StatusPill(text = value, tone = tone)
        }
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
    // The verdict and the sentence that explains it are computed together, so the chip can never disagree
    // with the paragraph beneath it. Declined keeps Skipped's own tone because it is the one outcome here
    // that is a choice — the launcher's comment below says the same about Android's result codes, and an
    // agreed dismissal must not wear the red of a refusal.
    val outcomeDetail = when (val last = state.outcome) {
        DeletionOutcome.None, DeletionOutcome.Asked -> null
        is DeletionOutcome.Removed -> OutcomeDetail(
            label = stringResource(R.string.status_done),
            tone = PillTone.Done,
            text = pluralStringResource(
                R.plurals.free_space_done,
                last.deleted,
                last.deleted,
                last.reclaimedBytes.toByteText(),
            ),
        )
        DeletionOutcome.NothingLeft -> OutcomeDetail(
            label = stringResource(R.string.status_skipped),
            tone = PillTone.Neutral,
            text = stringResource(R.string.free_space_nothing_left),
        )
        DeletionOutcome.Declined -> OutcomeDetail(
            label = stringResource(R.string.status_skipped),
            tone = PillTone.Skipped,
            text = stringResource(R.string.free_space_declined),
        )
        DeletionOutcome.Unavailable -> OutcomeDetail(
            label = stringResource(R.string.status_unavailable),
            tone = PillTone.Unavailable,
            text = stringResource(R.string.free_space_unavailable),
        )
        DeletionOutcome.Failed -> OutcomeDetail(
            label = stringResource(R.string.diag_failed),
            tone = PillTone.Unavailable,
            text = stringResource(R.string.free_space_failed),
        )
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
        modifier = modifier.fillMaxSize().background(onboardingBackdrop()),
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.free_space_title)) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        bottomBar = {
            if (state.candidates.isNotEmpty()) {
                Column(modifier = Modifier.fillMaxWidth().padding(SpaceLg), verticalArrangement = Arrangement.spacedBy(SpaceSm)) {
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
                                modifier = Modifier.size(MarkInline),
                                strokeWidth = RingStroke,
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
            contentPadding = androidx.compose.foundation.layout.PaddingValues(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceSm),
        ) {
            item {
                Text(
                    text = when {
                        state.nothingEligible -> stringResource(R.string.free_space_none)
                        else -> pluralStringResource(
                            R.plurals.free_space_header,
                            state.plan.eligibleCount,
                            state.plan.reclaimableBytes.toByteText(),
                            state.plan.eligibleCount,
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

            if (outcomeDetail != null) {
                item {
                    OutcomeCard(detail = outcomeDetail, onDismiss = viewModel::dismissOutcome)
                }
            }

            // Three cases, not two. A review that is still running and a review that came back with
            // nothing are different facts, and drawing a spinner for both leaves the screen promising an
            // answer that never arrives while its own header quotes real totals over zero rows.
            if (state.loading) {
                item { CircularProgressIndicator(modifier = Modifier.padding(top = SpaceXl)) }
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
                    pluralStringResource(
                        R.plurals.free_space_confirm_body,
                        state.selected.size,
                        state.selected.size,
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

/** One run's verdict: the word for it, how the app colours that word, and the sentence that says what happened. */
private data class OutcomeDetail(val label: String, val tone: PillTone, val text: String)

/**
 * The outcome of a deletion run, chip first and sentence second.
 *
 * Every other screen in the app answers "how did it go" with a [StatusPill] before it writes a paragraph,
 * and this one wrote only the paragraph — so the same five verdicts read as prose here and as chips
 * everywhere else. The chip sits beside Dismiss because both are short, and the sentence gets its own line:
 * the longest of them runs past a hundred characters, and a pill's row is not where a hundred characters go.
 */
@Composable
private fun OutcomeCard(detail: OutcomeDetail, onDismiss: () -> Unit) {
    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.material3.MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(SpaceMd),
            verticalArrangement = Arrangement.spacedBy(SpaceSm),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusPill(text = detail.label, tone = detail.tone)
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
            }
            Text(text = detail.text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CandidateRow(candidate: FreeUpSpaceCandidate, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = checked,
                role = Role.Checkbox,
                onValueChange = { onToggle() },
            )
            .padding(vertical = SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
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

/**
 * One failed item: what it was, how big, and the reason the queue recorded for stopping.
 *
 * Identified by name and size rather than a thumbnail because that is what a row on a settings screen has
 * room for — the picture is one tap away, in the viewer, where that photo's own Retry action already lives.
 * A null name is the item the index no longer holds, which is said rather than hidden: the failure is still
 * the user's to read.
 */
@Composable
private fun FailureRow(failure: BackupFailureItem, onOpen: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(vertical = SpaceXs),
    ) {
        Text(
            text = failure.displayName ?: stringResource(R.string.backup_failure_gone),
            style = LumoVaultType.itemTitle,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val reason = backupFailureReasonRes(failure.kind)?.let { stringResource(it) }
            ?: stringResource(R.string.backup_state_failed)
        val size = failure.sizeBytes?.toByteText()
        Text(
            text = if (size == null) reason else "$reason · $size",
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            )
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
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null, enabled = enabled)
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
            .clip(MaterialTheme.shapes.medium)
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

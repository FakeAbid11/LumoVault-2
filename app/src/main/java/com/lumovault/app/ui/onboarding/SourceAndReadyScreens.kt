package com.lumovault.app.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.TripOrigin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.ChecklistStatus
import com.lumovault.app.domain.model.OnboardingSummary

/**
 * Screen 5: one choice, persisted immediately, so leaving the flow mid-way does not lose it.
 * "Not now" is a real answer rather than a dead end — Settings can change it later.
 */
@Composable
fun BackupSourceScreen(
    selected: BackupSource?,
    selectedFolderCount: Int,
    onSelect: (BackupSource) -> Unit,
    onOpenFolders: () -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 5,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.source_title),
        primaryLabel = stringResource(R.string.source_action),
        onPrimary = onContinue,
        onBack = onBack,
        primaryEnabled = selected != null,
        modifier = modifier,
    ) {
        SourceOption(
            label = stringResource(R.string.source_all),
            selected = selected == BackupSource.AllMedia,
            onSelect = { onSelect(BackupSource.AllMedia) },
        )
        SourceOption(
            label = stringResource(R.string.source_folders),
            selected = selected == BackupSource.SelectedFolders,
            onSelect = {
                onSelect(BackupSource.SelectedFolders)
                onOpenFolders()
            },
            supporting = if (selected == BackupSource.SelectedFolders && selectedFolderCount > 0) {
                stringResource(R.string.source_selected_count, selectedFolderCount)
            } else {
                null
            },
        )
        SourceOption(
            label = stringResource(R.string.source_none),
            selected = selected == BackupSource.NotNow,
            onSelect = { onSelect(BackupSource.NotNow) },
            supporting = stringResource(R.string.source_none_detail),
        )
    }
}

@Composable
private fun SourceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    supporting: String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
        }
        if (supporting != null) {
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

/**
 * The folder picker. The list is deliberately empty: reading folders means querying MediaStore,
 * which is Phase 3's scanner, and inventing plausible-looking folder names here would be a lie the
 * user could act on. Selection persists against the folder keys the scanner will later emit.
 */
@Composable
fun FolderSelectionScreen(
    folders: List<String>,
    selectedFolders: List<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 5,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.folders_title),
        primaryLabel = stringResource(R.string.folders_action),
        onPrimary = onBack,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (folders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.folders_empty_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.folders_empty_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            folders.forEach { folder ->
                val checked = folder in selectedFolders
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle(folder) })
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Text(text = folder, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * Screen 6. Each tick is derived from live state by [OnboardingSummary], so nothing that did not
 * happen is shown as having happened — an unavailable Telegram build reads as "Unavailable", not ✓.
 */
@Composable
fun ReadyScreen(
    summary: OnboardingSummary,
    onStartBackup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 6,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.ready_title),
        description = stringResource(R.string.ready_description),
        primaryLabel = stringResource(R.string.ready_action),
        onPrimary = onStartBackup,
        onBack = onBack,
        modifier = modifier,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ReadyRow(stringResource(R.string.ready_telegram), summary.telegramItem.status)
            ReadyRow(stringResource(R.string.ready_media), summary.mediaItem.status)
            ReadyRow(stringResource(R.string.ready_notifications), summary.notificationsItem.status)
            ReadyRow(stringResource(R.string.ready_source), summary.backupSourceItem.status)
        }

        Text(
            text = stringResource(R.string.ready_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyRow(label: String, status: ChecklistStatus) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            imageVector = when (status) {
                ChecklistStatus.Done -> Icons.Filled.CheckCircle
                ChecklistStatus.Skipped -> Icons.Filled.RemoveCircleOutline
                ChecklistStatus.Missing -> Icons.Filled.RemoveCircleOutline
                ChecklistStatus.Unavailable -> Icons.Filled.TripOrigin
            },
            // The status is announced with the label, so the icon itself stays decorative.
            contentDescription = null,
            modifier = Modifier.size(22.dp),
            tint = when (status) {
                ChecklistStatus.Done -> MaterialTheme.colorScheme.primary
                ChecklistStatus.Unavailable -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        )

        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

        Text(
            text = stringResource(status.labelRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ChecklistStatus.labelRes(): Int = when (this) {
    ChecklistStatus.Done -> R.string.status_done
    ChecklistStatus.Skipped -> R.string.status_skipped
    ChecklistStatus.Missing -> R.string.status_not_set
    ChecklistStatus.Unavailable -> R.string.status_unavailable
}

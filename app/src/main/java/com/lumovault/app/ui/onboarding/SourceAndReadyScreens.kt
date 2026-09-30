package com.lumovault.app.ui.onboarding

import com.lumovault.app.domain.model.LocalFolder
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.lumovault.app.R
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.ChecklistStatus
import com.lumovault.app.domain.model.OnboardingSummary
import com.lumovault.app.ui.theme.IconLeading
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

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
                pluralStringResource(R.plurals.backup_folders_selected, selectedFolderCount, selectedFolderCount)
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

/**
 * A whole card rather than a naked radio row: the choice *is* the screen, so it gets a surface.
 *
 * The selected card carries the accent border and the accent container; the others a hairline on the
 * quiet tier — how a settings screen says "this one" without leaning on the dot alone. The semantics
 * stay on the card, so TalkBack reads one selectable per choice, not a card and a radio fighting
 * over the same tap.
 */
@Composable
private fun SourceOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    supporting: String? = null,
) {
    val shape = MaterialTheme.shapes.medium
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onSelect,
            ),
        shape = shape,
        border = BorderStroke(
            width = if (selected) SelectedBorderWidth else DefaultBorderWidth,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(SpaceLg),
            verticalArrangement = Arrangement.spacedBy(SpaceXs),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                RadioButton(selected = selected, onClick = null)
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }
            if (supporting != null) {
                Text(
                    text = supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = SublineIndent),
                )
            }
        }
    }
}

/**
 * The folder picker, shared with Settings by the record behind it.
 *
 * Rows are labelled by the folder's own name — with its parent when two folders share one — and ticked by
 * their normalized `RELATIVE_PATH`, which is the value `autoBackupCandidates` compares with. The earlier
 * shape stored the label it showed, so a folder chosen here could match no row in the index at all.
 * Nothing is invented: an unscanned device has no folders to list and says so.
 */
@Composable
fun FolderSelectionScreen(
    folders: List<LocalFolder>,
    selectedFolders: List<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingScaffold(
        step = 5,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.folders_title),
        primaryLabel = stringResource(R.string.folders_action),
        // Forward, not back: every tick is committed the moment it is tapped, so this screen has no
        // draft to lose — and a "Continue" that pops to Sources leaves a user who chose folders with
        // no way to finish setup.
        onPrimary = onContinue,
        onBack = onBack,
        modifier = modifier,
    ) {
        if (folders.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Column(
                    modifier = Modifier.padding(SpaceLg),
                    verticalArrangement = Arrangement.spacedBy(SpaceSm),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Filled.FolderOff,
                        contentDescription = null,
                        modifier = Modifier.size(NoticeGlyphSize),
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
                val repeated = folders.count { it.displayName == folder.displayName } > 1
                val checked = folder.relativePath in selectedFolders
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = checked,
                            role = Role.Checkbox,
                            onValueChange = { onToggle(folder.relativePath) },
                        )
                        .padding(vertical = SpaceSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpaceMd),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = folder.displayName, style = MaterialTheme.typography.bodyLarge)
                        if (repeated && folder.parentLabel.isNotBlank()) {
                            Text(
                                text = folder.parentLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    StatusPill(
                        text = folder.mediaCount.toString(),
                        tone = PillTone.Neutral,
                    )
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
    // "Start backup" only when finishing will start one: with no session, no media access or the source
    // "Not now", completing the flow writes a flag and schedules nothing — or a pass that will find no
    // provider to read — and a button named after work that will not happen is the last lie a setup
    // screen tells.
    val willStartBackup = summary.telegramItem.status == ChecklistStatus.Done &&
        summary.mediaItem.status == ChecklistStatus.Done &&
        summary.backupSourceItem.status == ChecklistStatus.Done

    OnboardingScaffold(
        step = 6,
        totalSteps = ONBOARDING_STEPS,
        title = stringResource(R.string.ready_title),
        description = stringResource(R.string.ready_description),
        primaryLabel = stringResource(
            if (willStartBackup) R.string.ready_action else R.string.ready_action_finish,
        ),
        onPrimary = onStartBackup,
        onBack = onBack,
        modifier = modifier,
    ) {
        // The one celebratory mark of the flow, in the accent gradient the welcome disc opened with —
        // the tick the checklist rows below then break out one by one.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(ReadyHeroSize)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primary,
                                MaterialTheme.colorScheme.primaryContainer,
                            ),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(ReadyHeroGlyph),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(SpaceMd), modifier = Modifier.fillMaxWidth()) {
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
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
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
            modifier = Modifier.size(IconLeading),
            tint = when (status) {
                ChecklistStatus.Done -> MaterialTheme.colorScheme.primary
                ChecklistStatus.Unavailable -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            },
        )

        Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))

        StatusPill(
            text = stringResource(status.labelRes()),
            tone = status.pillTone(),
        )
    }
}

private fun ChecklistStatus.labelRes(): Int = when (this) {
    ChecklistStatus.Done -> R.string.status_done
    ChecklistStatus.Skipped -> R.string.status_skipped
    ChecklistStatus.Missing -> R.string.status_not_set
    ChecklistStatus.Unavailable -> R.string.status_unavailable
}

private fun ChecklistStatus.pillTone(): PillTone = when (this) {
    ChecklistStatus.Done -> PillTone.Done
    ChecklistStatus.Skipped -> PillTone.Skipped
    ChecklistStatus.Missing -> PillTone.Missing
    ChecklistStatus.Unavailable -> PillTone.Unavailable
}

/** A sub-line starts under its row's label, not under its radio button, so it clears the control and its gap. */
private val SublineIndent = 48.dp

/** The empty-folders notice is a statement, not a row glyph, so it is bigger than the mark scale. */
private val NoticeGlyphSize = 28.dp

/** Option cards read as one surface: the accent edge is visible without being a frame. */
private val SelectedBorderWidth = 1.5.dp
private val DefaultBorderWidth = 1.dp

/** The last screen's one mark, at the size the checklist's own glyphs stack up to. */
private val ReadyHeroSize = 56.dp
private val ReadyHeroGlyph = 30.dp

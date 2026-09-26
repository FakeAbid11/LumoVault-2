package com.lumovault.app.ui.backup

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.R
import com.lumovault.app.domain.model.BackupSource
import com.lumovault.app.domain.model.LocalFolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Which folders automatic backup may take media from, as one screen's worth of state.
 *
 * [loading] is separate from an empty list because the two look identical otherwise and mean opposite
 * things: one is a device still scanning, the other is a device with nothing to back up.
 */
data class BackupFoldersUiState(
    val folders: List<LocalFolder> = emptyList(),
    val selected: Set<String> = emptySet(),
    /** What is saved, in the same words the Backup & storage hub uses for it. */
    val sourceLine: BackupSourceLine = BackupSourceLine(null, 0),
    val loading: Boolean = true,
)

/**
 * The Settings side of the same two columns onboarding writes.
 *
 * There is deliberately no second preference store: this screen saves through
 * [com.lumovault.app.domain.usecase.ApplyBackupSelectionUseCase], which is the one place `source_selection`
 * and `selected_folders` are written and the schedule is re-read. A folder chosen here is the folder
 * onboarding would show, it queues what is already inside it on the spot, and it queues what appears later on
 * the same periodic pass — no second queue, and no upload path that could disagree with the first.
 */
class BackupFoldersViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    /** The draft, until [save]. Cancel is then just dropping it — nothing has been written. */
    private val draft = MutableStateFlow<Set<String>?>(null)

    private val progress = container.onboardingRepository.progress

    val folders = container.mediaOrganizationRepository.observeBackupFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val uiState: StateFlow<BackupFoldersUiState> = combine(
        progress,
        folders,
        draft,
    ) { current, available, pending ->
        BackupFoldersUiState(
            folders = available,
            selected = pending ?: current.selectedFolders.toSet(),
            sourceLine = BackupSourceLine(current.backupSource, current.selectedFolders.size),
            loading = available.isEmpty() && current.backupSource == null,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), BackupFoldersUiState())

    fun toggle(path: String) {
        val current = draft.value ?: uiState.value.selected
        draft.value = if (path in current) current - path else current + path
    }

    /**
     * Writes the choice, and starts what the choice implies.
     *
     * An empty selection is saved as [BackupSource.SelectedFolders] with no folders rather than silently
     * becoming "everything": the queue refuses to queue anything when folders were chosen and none are, and
     * changing that here would make the screen's own checkboxes a lie. What an empty selection does mean is
     * that whatever was lined up to be sent from the folders now left out is taken back out — that is the
     * other half of the same call, and the reason this screen does not write the settings row itself.
     */
    fun save() {
        val chosen = draft.value ?: return
        draft.value = null
        viewModelScope.launch {
            container.applyBackupSelection.apply(BackupSource.SelectedFolders, chosen.toList())
        }
    }

    fun saveAllMedia() {
        draft.value = null
        viewModelScope.launch { container.applyBackupSelection.apply(BackupSource.AllMedia, emptyList()) }
    }

    fun cancel() {
        draft.value = null
    }
}

/**
 * The folder picker reached from Backup & storage.
 *
 * Rows are keyed by the normalized relative path, never by the label: two folders called `Telegram` under
 * different parents are two checkboxes, and ticking one must not select the other.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupFoldersScreen(
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupFoldersViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_folders_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            viewModel.save()
                            onNavigateUp()
                        },
                        enabled = state.folders.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.backup_folders_explainer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    text = state.sourceLine.label(),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                OutlinedButton(
                    onClick = {
                        viewModel.saveAllMedia()
                        onNavigateUp()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.backup_folders_back_up_everything))
                }
            }

            item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

            when {
                state.loading -> item {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 12.dp))
                }

                state.folders.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.backup_folders_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> items(state.folders, key = { it.relativePath }) { folder ->
                    val repeated = state.folders.count { it.displayName == folder.displayName } > 1
                    val checked = folder.relativePath in state.selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = checked,
                                role = Role.Checkbox,
                                onValueChange = { viewModel.toggle(folder.relativePath) },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                        Text(
                            text = pluralStringResource(
                                R.plurals.album_items_count,
                                folder.mediaCount,
                                folder.mediaCount,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

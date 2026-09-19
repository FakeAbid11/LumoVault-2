package com.lumovault.lumovault.features.onboarding.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.gallery.data.service.DeviceFolder

/**
 * Folder selection screen — choose which device folders to back up.
 *
 * Ported from folder_selection_screen.dart. Folders come from
 * `MediaScannerService.listFolders()` (MediaStore buckets); toggles write the
 * selection into [OnboardingViewModel.selectedFolders], which
 * [OnboardingViewModel.completeOnboarding] persists as `includedFolders`.
 *
 * The zero-selection warning dialog is carried over intact: the original
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionScreen(
    onNext: () -> Unit,
    onBack: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val folderList by viewModel.folderList.collectAsStateWithLifecycle()
    val selected by viewModel.selectedFolders.collectAsStateWithLifecycle()
    var showEmptyWarning by rememberSaveable { mutableStateOf(false) }

    val proceed = {
        if (selected.isEmpty()) showEmptyWarning = true else onNext()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_folders_title)) },
                actions = {
                    TextButton(onClick = { proceed() }) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            when {
                folderList.isLoading -> {
                    Spacer(Modifier.weight(1f))
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    Spacer(Modifier.weight(1f))
                }
                folderList.failed -> {
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.weight(1f))
                        Icon(
                            Icons.Default.FolderOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.onboarding_folders_load_error_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.onboarding_folders_load_error_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadFolders() }) {
                            Text(stringResource(R.string.onboarding_folders_retry))
                        }
                        Spacer(Modifier.weight(1f))
                    }
                }
                else -> {
                    FolderList(
                        folders = folderList.folders,
                        selected = selected,
                        onToggle = viewModel::toggleFolder,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Row(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_back))
                }
                Spacer(Modifier.width(16.dp))
                Button(onClick = { proceed() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.onboarding_continue))
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }

    if (showEmptyWarning) {
        AlertDialog(
            onDismissRequest = { showEmptyWarning = false },
            title = { Text(stringResource(R.string.onboarding_folders_none_title)) },
            text = { Text(stringResource(R.string.onboarding_folders_none_message)) },
            confirmButton = {
                TextButton(onClick = { showEmptyWarning = false; onNext() }) {
                    Text(stringResource(R.string.onboarding_folders_none_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyWarning = false }) {
                    Text(stringResource(R.string.onboarding_folders_none_go_back))
                }
            },
        )
    }
}

 * passed with no warning while the backup scheduler silently rejected every
 * photo, so Continue-with-none now asks first.
 */
@Composable
private fun FolderList(
    folders: List<DeviceFolder>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (folders.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.onboarding_folders_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.weight(1f))
        }
        return
    }
    LazyColumn(modifier = modifier) {
        items(folders, key = { it.bucketId }) { folder ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = folder.bucketId in selected,
                        onCheckedChange = { onToggle(folder.bucketId) },
                    )
                    Column {
                        Text(folder.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(
                                R.string.onboarding_folder_item_count,
                                folder.totalItems,
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

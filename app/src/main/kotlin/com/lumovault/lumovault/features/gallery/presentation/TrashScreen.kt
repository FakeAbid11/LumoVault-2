package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

/**
 * The trash bin.
 *
 * Ported from lib/features/trash/presentation/screens/trash_screen.dart. Items
 * here carry `is_trashed`; retention is governed by `trashDurationDays` in
 * settings (30 by default), so the banner states what happens to a file left
 * here rather than implying it is gone already.
 *
 * Delete-permanently is destructive and unrecoverable, so it asks once.
 * Restore is not, and does not. Long-press selects so a restore or delete can
 * be aimed at one item rather than the whole bin.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: MediaCollectionsViewModel = hiltViewModel(),
) {
    val items by viewModel.trashed.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.trash_title)
                        } else {
                            stringResource(R.string.collection_n_selected, selected.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected.isEmpty()) onBack() else selected = emptySet()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = {
                            selected.forEach { viewModel.restoreFromTrash(it) }
                            selected = emptySet()
                        }) {
                            Icon(Icons.Default.Restore, contentDescription = stringResource(R.string.action_restore))
                        }
                        IconButton(onClick = { pendingDelete = selected }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete_permanently))
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyCollection(
                title = stringResource(R.string.trash_empty_title),
                explanation = stringResource(R.string.trash_empty_explanation),
                modifier = Modifier.padding(padding),
            )
        } else {
            Column(modifier = Modifier.padding(padding)) {
                RetentionBanner(itemCount = items.size, retentionDays = settings.trashDurationDays)
                MediaGrid(
                    items = items,
                    onItemClick = { index ->
                        val item = items.getOrNull(index) ?: return@MediaGrid
                        if (selected.isNotEmpty()) {
                            selected = selected.toggle(item.localId)
                        } else {
                            onOpenItem(index, items)
                        }
                    },
                    selection = selected,
                    onItemLongClick = { localId ->
                        selected = selected.toggle(localId)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pendingDelete?.let { ids ->
        ConfirmDeleteDialog(
            count = ids.size,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.deletePermanently(ids)
                selected = selected - ids
                pendingDelete = null
            },
        )
    }
}

/**
 * States the retention promise the repository actually keeps.
 *
 * Ported from trash_screen.dart's banner, which reads `trashDurationDays`
 * rather than hardcoding a number: 0 means "until you delete them" and any
 * other value is the real purge interval enforced by the expired-trashed
 * purge on scan. Lying here would hide an auto-delete the user needs to know
 * about.
 */
@Composable
private fun RetentionBanner(itemCount: Int, retentionDays: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            val text = if (retentionDays <= 0) {
                stringResource(R.string.trash_retention_never)
            } else {
                stringResource(R.string.trash_retention_days, retentionDays)
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Set<String>.toggle(element: String): Set<String> =
    if (element in this) this - element else this + element

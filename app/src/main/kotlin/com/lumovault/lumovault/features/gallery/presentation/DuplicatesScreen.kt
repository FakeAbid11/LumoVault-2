package com.lumovault.lumovault.features.gallery.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.core.ui.formatBytes

/**
 * Groups of photos sharing a SHA-256 content hash.
 *
 * Ported from lib/features/duplicates/presentation/screens/duplicates_screen.dart.
 * Grouping is exact-hash equality — the original deliberately has no perceptual
 * or near-duplicate detection, so a visually identical re-save does not appear
 * here. Resolution is always deletion of the extras: "keep newest" and "keep
 * oldest" are the two named shortcuts, and the user picks the copies by hand
 * for everything else. There is no merge action.
 *
 * Deletion is permanent, so every path asks first with [ConfirmDeleteDialog].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DuplicatesScreen(
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: DuplicateViewModel = hiltViewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<Set<String>?>(null) }

    // Back exits selection mode rather than the screen, matching the
    // original's PopScope — a stray back press should not throw away a
    // selection the user built one tap at a time.
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }

    val duplicates = remember(groups) { groups.sumOf { it.size - 1 } }
    val reclaimable = remember(groups) {
        groups.sumOf { group -> group.first().fileSize * (group.size - 1) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selected.isEmpty()) {
                            stringResource(R.string.duplicates_title)
                        } else {
                            stringResource(R.string.collection_n_selected, selected.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selected.isEmpty()) onBack() else selected = emptySet()
                    }) {
                        Icon(
                            if (selected.isEmpty()) {
                                Icons.AutoMirrored.Filled.ArrowBack
                            } else {
                                Icons.Default.Close
                            },
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    if (selected.isNotEmpty()) {
                        IconButton(onClick = { pendingDelete = selected }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.action_delete_permanently),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (groups.isEmpty()) {
            EmptyCollection(
                title = stringResource(R.string.duplicates_empty_title),
                explanation = stringResource(R.string.duplicates_empty_explanation),
                modifier = Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(16.dp),
            ) {
                item {
                    DuplicatesSummary(groupCount = groups.size, duplicates = duplicates, reclaimable = reclaimable)
                }
                items(groups, key = { group -> group.first().fileHash }) { group ->
                    DuplicateGroupCard(
                        group = group,
                        selected = selected,
                        onToggle = { localId -> selected = selected.toggle(localId) },
                        onOpenItem = { index -> onOpenItem(index, group) },
                        onKeepNewest = { viewModel.keepNewest(group) },
                        onKeepOldest = { viewModel.keepOldest(group) },
                        onSelectAll = { selected = selected + group.map { it.localId } },
                        onDeselectAll = { selected = selected - group.map { it.localId } },
                    )
                }
            }
        }
    }

    pendingDelete?.let { ids ->
        ConfirmDeleteDialog(
            count = ids.size,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                viewModel.deleteSelected(ids)
                selected = selected - ids
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun DuplicatesSummary(groupCount: Int, duplicates: Int, reclaimable: Long) {
    Text(
        "$groupCount group(s) · $duplicates duplicate(s) · ${formatBytes(reclaimable)} reclaimable",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun DuplicateGroupCard(
    group: List<MediaItemEntity>,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onOpenItem: (Int) -> Unit,
    onKeepNewest: () -> Unit,
    onKeepOldest: () -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val reclaimable = group.first().fileSize * (group.size - 1)
    val allSelected = group.all { it.localId in selected }

    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "${group.size} copies · ${formatBytes(reclaimable)} reclaimable",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        group.firstOrNull()?.fileName ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(
                                        if (allSelected) R.string.action_deselect_all else R.string.action_select_all,
                                    ),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                if (allSelected) onDeselectAll() else onSelectAll()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_keep_newest)) },
                            onClick = {
                                menuOpen = false
                                onKeepNewest()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_keep_oldest)) },
                            onClick = {
                                menuOpen = false
                                onKeepOldest()
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            // A horizontal strip rather than a grid: one group is a row of
            // alternates, and the original sized it at 100dp for the same
            // reason — wide enough to judge, short enough to scroll past.
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(100.dp),
            ) {
                items(group, key = { it.localId }) { item ->
                    DuplicateThumbnail(
                        item = item,
                        selected = item.localId in selected,
                        onClick = {
                            if (selected.isNotEmpty()) onToggle(item.localId)
                            else onOpenItem(group.indexOf(item))
                        },
                        onLongClick = { onToggle(item.localId) },
                        modifier = Modifier.size(100.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DuplicateThumbnail(
    item: MediaItemEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(item.filePath).build(),
            contentDescription = item.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

/**
 * The irreversible-delete confirmation, shared with the trash screen.
 *
 * Ported from `_confirmDelete` in duplicates_screen.dart and its twin in
 * trash_screen.dart: an error-toned icon, a count-specific title, and a
 * warning that this cannot be undone. Deleting an extras selection is the
 * only destructive action on this screen, so it is the one that asks.
 */
@Composable
fun ConfirmDeleteDialog(
    count: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text(stringResource(R.string.confirm_delete_title)) },
        text = {
            Text(stringResource(R.string.confirm_delete_message, count))
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private fun Set<String>.toggle(element: String): Set<String> =
    if (element in this) this - element else this + element

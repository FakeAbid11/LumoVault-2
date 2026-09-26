package com.lumovault.app.ui.screens.albums

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.annotation.StringRes
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.components.MediaCell
import com.lumovault.app.ui.navigation.AlbumTarget
import com.lumovault.app.ui.theme.GridCellMinSize
import com.lumovault.app.ui.theme.GridSpacing
import com.lumovault.app.ui.theme.GroupCardCorner

/**
 * One album: its items, and the organisation actions over the ones the user selects.
 *
 * Which actions appear is the difference between the two kinds of album rather than a flag: a system
 * album's membership follows from what its files are, so it offers favourite, archive and Trash but never
 * "remove from album"; Trash itself offers restore and permanent deletion and nothing else.
 *
 * The destructive path is deliberately slow. "Delete forever" confirms once here, then hands Android a
 * consent request that names the files, and only a confirmed result removes anything — and what it removes
 * is local: the index row, the organisation, the memberships. The backup record and the message in the
 * user's channel stay, which PRD section 72 treats as the point rather than an oversight.
 */
@Composable
fun AlbumDetailScreen(
    target: AlbumTarget?,
    onNavigateUp: () -> Unit,
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumDetailViewModel = viewModel(),
) {
    // A target this build cannot name — a system-album string from a newer version, say — is left
    // unopened, and the screen shows an empty album. Guessing at which album the user meant would be
    // worse than admitting the link means nothing here.
    LaunchedEffect(target) { target?.let(viewModel::open) }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val libraryItems by viewModel.libraryItems.collectAsStateWithLifecycle()

    var renaming by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<Confirmation?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deletionUnsupported by remember { mutableStateOf(false) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> viewModel.onDeletionResult(result.resultCode) }

    // Back leaves selection mode rather than the album, matching the Photos grid.
    BackHandler(enabled = state.selection.isNotEmpty()) { viewModel.clearSelection() }

    val isTrash = state.systemAlbum == SystemAlbum.Trash

    Column(modifier = modifier.fillMaxSize()) {
        AlbumHeader(
            title = state.userAlbum?.name
                ?: state.systemAlbum?.let { stringResource(it.titleRes) }
                ?: state.folderName.orEmpty(),
            itemCount = state.items?.size ?: 0,
            isUserAlbum = state.isUserAlbum,
            explainer = state.systemAlbum?.let { album ->
                when (album) {
                    SystemAlbum.Trash -> stringResource(R.string.trash_explainer)
                    SystemAlbum.Archive -> stringResource(R.string.organization_archive_hint)
                    SystemAlbum.RecentlyAdded -> stringResource(R.string.recently_added_explainer)
                    else -> stringResource(R.string.system_album_readonly)
                }
            } ?: state.folderName?.let { stringResource(R.string.folder_album_readonly) },
            onAddMedia = { adding = true },
            onRename = { renaming = true },
            onDeleteAlbum = { confirming = Confirmation.DeleteAlbum },
            onEmptyTrash = { confirming = Confirmation.EmptyTrash },
            isTrash = isTrash,
        )

        if (deletionUnsupported) {
            NoticeBanner(stringResource(R.string.trash_delete_unsupported))
        }

        val loadedItems = state.items
        if (loadedItems == null) {
            // Room has not answered yet. "No items" here would be a false claim on every album open,
            // and a failed load would be indistinguishable from an empty album.
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            MediaGrid(
                items = loadedItems,
                favoriteIds = state.favoriteIds,
                selection = state.selection,
                onCellClick = { mediaId ->
                    if (state.selection.isEmpty()) onOpenMedia(mediaId) else viewModel.onCellClick(mediaId)
                },
                onCellLongClick = viewModel::onCellLongClick,
                onLoadMore = viewModel::loadMore,
                modifier = Modifier.weight(1f),
            )
        }

        if (state.selection.isNotEmpty()) {
            AlbumActionBar(
                selectionSize = state.selection.size,
                canEditMembership = state.isUserAlbum,
                isTrash = isTrash,
                onClear = viewModel::clearSelection,
                onFavorite = { viewModel.setFavorite(true) },
                onUnfavorite = { viewModel.setFavorite(false) },
                onArchive = { viewModel.setArchived(true) },
                onUnarchive = { viewModel.setArchived(false) },
                onTrash = { confirming = Confirmation.MoveToTrash },
                onRestore = viewModel::restoreFromTrash,
                onRemoveFromAlbum = { confirming = Confirmation.RemoveFromAlbum },
                onDeleteForever = { confirming = Confirmation.DeleteForever },
            )
        }
    }

    if (adding) {
        AddMediaSheet(
            albumName = state.userAlbum?.name.orEmpty(),
            items = libraryItems,
            alreadyMemberOf = state.memberIds,
            onDismiss = { adding = false },
            onConfirm = { ids ->
                adding = false
                viewModel.addMedia(ids)
            },
        )
    }

    if (renaming) {
        AlbumNameDialog(
            title = R.string.album_rename_title,
            confirm = R.string.album_rename_action,
            initial = state.userAlbum?.name.orEmpty(),
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                viewModel.rename(name)
            },
        )
    }

    when (confirming) {
        Confirmation.DeleteAlbum -> ConfirmDialog(
            title = R.string.album_delete_title,
            body = R.string.album_delete_body,
            action = R.string.album_delete_action,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                viewModel.deleteAlbum(onDeleted = onNavigateUp)
            },
        )

        Confirmation.DeleteForever -> ConfirmDialog(
            title = R.string.trash_delete_confirm_title,
            body = R.string.trash_delete_confirm_body,
            action = R.string.trash_delete_forever_action,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                viewModel.deleteForever(
                    launch = { request -> deleteLauncher.launch(request) },
                    onUnsupported = { deletionUnsupported = true },
                )
            },
        )

        Confirmation.EmptyTrash -> ConfirmDialog(
            title = R.string.trash_empty_confirm_title,
            body = R.string.trash_empty_confirm_body,
            action = R.string.trash_empty_action,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                viewModel.emptyTrash(
                    launch = { request -> deleteLauncher.launch(request) },
                    onUnsupported = { deletionUnsupported = true },
                )
            },
        )

        // The same confirmation the viewer shows for one item: trashing a selection is the same act
        // at a larger scale, and neither has an undo from this screen.
        Confirmation.MoveToTrash -> ConfirmDialog(
            title = R.string.viewer_trash_title,
            body = R.string.viewer_trash_body,
            action = R.string.organization_trash_action,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                viewModel.moveToTrash()
            },
        )

        Confirmation.RemoveFromAlbum -> ConfirmDialog(
            title = R.string.album_remove_confirm_title,
            body = R.string.album_remove_confirm_body,
            action = R.string.album_remove_from_album_action,
            onDismiss = { confirming = null },
            onConfirm = {
                confirming = null
                viewModel.removeFromAlbum()
            },
        )

        null -> Unit
    }
}

/** What the user has agreed to, between the confirmation and the action. */
private enum class Confirmation { DeleteAlbum, DeleteForever, EmptyTrash, MoveToTrash, RemoveFromAlbum }

@Composable
private fun AlbumHeader(
    title: String,
    itemCount: Int,
    isUserAlbum: Boolean,
    isTrash: Boolean,
    explainer: String?,
    onAddMedia: () -> Unit,
    onRename: () -> Unit,
    onDeleteAlbum: () -> Unit,
    onEmptyTrash: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = pluralStringResource(R.plurals.album_items_count, itemCount, itemCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (explainer != null) {
            Text(
                text = explainer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isUserAlbum) {
                OutlinedButton(onClick = onAddMedia) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(
                        text = stringResource(R.string.album_add_media_action),
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                TextButton(onClick = onRename) { Text(stringResource(R.string.album_rename_title)) }
                TextButton(onClick = onDeleteAlbum) { Text(stringResource(R.string.album_delete_action)) }
            }
            if (isTrash) {
                TextButton(onClick = onEmptyTrash) { Text(stringResource(R.string.trash_empty_action)) }
            }
        }
    }
}

/**
 * The grid, loaded in a window like the timeline's.
 *
 * `itemsIndexed` rather than `items` because the tail test needs the row index, and a screenshot album of
 * 60,000 images must not be materialised to draw a screenful of its start.
 */
@Composable
private fun MediaGrid(
    items: List<Media>,
    favoriteIds: Set<Long>,
    selection: Set<Long>,
    onCellClick: (Long) -> Unit,
    onCellLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(gridState, items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
            if (items.isNotEmpty() && lastVisible >= items.size - LOAD_AHEAD) onLoadMore()
        }
    }

    if (items.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.albums_no_items),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(32.dp),
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinSize),
        state = gridState,
        contentPadding = PaddingValues(GridSpacing),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
        modifier = modifier.fillMaxSize(),
    ) {
        itemsIndexed(items, key = { _, media -> media.id }) { _, media ->
            MediaCell(
                media = media,
                favorite = media.id in favoriteIds,
                selected = media.id in selection,
                onClick = { onCellClick(media.id) },
                onLongClick = { onCellLongClick(media.id) },
            )
        }
    }
}

/**
 * Actions over the selection.
 *
 * Favourite and archive appear as both directions because an album detail screen is where a user changes
 * their mind, and an action that only ever adds is a button that breaks once the item is already marked.
 */
@Composable
private fun AlbumActionBar(
    selectionSize: Int,
    canEditMembership: Boolean,
    isTrash: Boolean,
    onClear: () -> Unit,
    onFavorite: () -> Unit,
    onUnfavorite: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onTrash: () -> Unit,
    onRestore: () -> Unit,
    onRemoveFromAlbum: () -> Unit,
    onDeleteForever: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(GridSpacing),
        shape = RoundedCornerShape(GroupCardCorner),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            // Scrollable rather than compressed: Trash plus six organisation marks plus a count and a
            // Clear do not fit a narrow phone, and squeezing them shrinks every tap target at once.
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(R.string.album_selected_count, selectionSize),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(end = 4.dp),
            )
            if (isTrash) {
                ActionIcon(Icons.Filled.Restore, R.string.trash_restore_action, onRestore)
                ActionIcon(Icons.Filled.Delete, R.string.trash_delete_forever_action, onDeleteForever)
            } else {
                ActionIcon(Icons.Filled.Favorite, R.string.organization_favorite_action, onFavorite)
                ActionIcon(Icons.Filled.FavoriteBorder, R.string.organization_unfavorite_action, onUnfavorite)
                ActionIcon(Icons.Filled.Archive, R.string.organization_archive_action, onArchive)
                ActionIcon(Icons.Filled.Unarchive, R.string.organization_unarchive_action, onUnarchive)
                ActionIcon(Icons.Filled.Delete, R.string.organization_trash_action, onTrash)
                if (canEditMembership) {
                    ActionIcon(Icons.Filled.PhotoLibrary, R.string.album_remove_from_album_action, onRemoveFromAlbum)
                }
            }
            TextButton(onClick = onClear, modifier = Modifier.padding(start = 2.dp)) {
                Text(stringResource(R.string.backup_selection_clear))
            }
        }
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun ConfirmDialog(title: Int, body: Int, action: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) }
        },
    )
}

/**
 * The picker that adds items to a user album.
 *
 * Ticked rows are the ones already in the album, and confirming adds only what is newly chosen — so the
 * button's number is the number of rows that will change, not the size of the album afterwards.
 */
@Composable
private fun AddMediaSheet(
    albumName: String,
    items: List<Media>,
    alreadyMemberOf: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Collection<Long>) -> Unit,
) {
    var chosen by remember { mutableStateOf<Set<Long>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_add_sheet_title, albumName)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Said out loud because those rows are ticked and cannot be unticked: without the line, a
                // member that stays selected after a tap looks like a control that does not work.
                if (alreadyMemberOf.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.album_already_in_album),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (items.isEmpty()) {
                    Text(
                        text = stringResource(R.string.album_library_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = GridCellMinSize),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                    verticalArrangement = Arrangement.spacedBy(GridSpacing),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = rememberLazyGridState(),
                ) {
                    items(items, key = { media -> media.id }) { media ->
                        val added = media.id in alreadyMemberOf
                        Box {
                            MediaCell(
                                media = media,
                                selected = media.id in chosen || added,
                                onClick = if (added) null else {
                                    {
                                        chosen = if (media.id in chosen) chosen - media.id else chosen + media.id
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(chosen) },
                enabled = chosen.isNotEmpty(),
            ) {
                Text(stringResource(R.string.album_add_sheet_add_action, chosen.size))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) } },
    )
}

@Composable
private fun NoticeBanner(text: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridSpacing, vertical = 4.dp),
        shape = RoundedCornerShape(GroupCardCorner),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(10.dp),
        )
    }
}


/** Rows fetched ahead of the viewport edge, so scrolling never reaches a blank tail. */
private const val LOAD_AHEAD = 24

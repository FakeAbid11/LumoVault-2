package com.lumovault.lumovault.features.albums.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.presentation.EmptyCollection
import com.lumovault.lumovault.features.gallery.presentation.MediaGrid

/**
 * One user album's contents.
 *
 * Ported from the custom-album branch of album_detail_screen.dart. Members are
 * re-read after every mutation (album_items has no Room flow); remove-from-album
 * asks once because it detaches the item without deleting it, which the user
 * can mistake for a delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: AlbumsViewModel = hiltViewModel(),
) {
    val album by viewModel.openAlbum.collectAsStateWithLifecycle()
    val items by viewModel.openAlbumItems.collectAsStateWithLifecycle()

    LaunchedEffect(albumId) { viewModel.openAlbum(albumId) }

    var menuOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(album?.name ?: stringResource(R.string.albums_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.action_more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.albums_rename)) },
                            onClick = { menuOpen = false; showRename = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.albums_delete)) },
                            onClick = { menuOpen = false; showDelete = true },
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyCollection(
                title = stringResource(R.string.album_detail_empty_title),
                explanation = stringResource(R.string.album_detail_empty_explanation),
                modifier = Modifier.padding(padding),
            )
        } else {
            MediaGrid(
                items = items,
                onItemClick = { index -> onOpenItem(index, items) },
                onItemLongClick = { pendingRemove = it },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showRename) {
        RenameAlbumDialog(
            current = album?.name.orEmpty(),
            onDismiss = { showRename = false },
            onRename = { viewModel.renameAlbum(albumId, it); showRename = false },
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.albums_delete_confirm_title)) },
            text = { Text(stringResource(R.string.albums_delete_confirm_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAlbum(albumId); showDelete = false; onBack() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
    pendingRemove?.let { mediaId ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text(stringResource(R.string.albums_remove_title)) },
            text = { Text(stringResource(R.string.albums_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFromAlbum(albumId, mediaId)
                    pendingRemove = null
                }) { Text(stringResource(R.string.albums_remove_action)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun RenameAlbumDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.albums_rename)) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.albums_name_label)) })
        },
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.albums_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

package com.lumovault.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlbum
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.ui.navigation.AlbumTarget
import com.lumovault.app.ui.screens.albums.AlbumCard
import com.lumovault.app.ui.screens.albums.AlbumsViewModel
import com.lumovault.app.ui.screens.albums.icon
import com.lumovault.app.ui.screens.albums.titleRes

/**
 * The Albums destination: the system collections the library implies, and the albums the user made.
 *
 * Nothing here queries MediaStore or Room — the screen reads one [AlbumsViewModel] state, and a count of
 * zero on a system tile is shown as zero rather than hidden, because a grid that loses tiles while you
 * look at it is the screen moving, not the library changing.
 */
@Composable
fun AlbumsScreen(
    onOpenAlbum: (AlbumTarget) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumsViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize()) {
        Button(
            onClick = { creating = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SectionPadding, vertical = 8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.AddPhotoAlbum,
                contentDescription = null,
                modifier = Modifier.padding(end = 8.dp),
            )
            Text(stringResource(R.string.albums_create_action))
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = CardMinSize),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(SectionPadding),
            horizontalArrangement = Arrangement.spacedBy(CardGap),
            verticalArrangement = Arrangement.spacedBy(CardGap),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "system-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(R.string.albums_section_system)
            }

            SystemAlbum.entries.forEach { album ->
                item(key = "system-${album.name}") {
                    AlbumCard(
                        title = stringResource(album.titleRes),
                        subtitle = pluralStringResource(R.plurals.album_items_count, state.counts.of(album), state.counts.of(album)),
                        icon = album.icon,
                        onClick = { onOpenAlbum(AlbumTarget.System(album)) },
                    )
                }
            }

            item(key = "user-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(R.string.albums_section_user)
            }

            if (state.userAlbums.isEmpty()) {
                item(key = "user-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        text = stringResource(R.string.albums_empty_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }

            state.userAlbums.forEach { album ->
                item(key = "album-${album.id}") {
                    AlbumCard(
                        title = album.name,
                        subtitle = pluralStringResource(R.plurals.album_items_count, album.itemCount, album.itemCount),
                        coverUri = album.coverUri,
                        onClick = { onOpenAlbum(AlbumTarget.User(album.id)) },
                    )
                }
            }
        }
    }

    if (creating) {
        AlbumNameDialog(
            title = R.string.album_create_title,
            confirm = R.string.album_create_action,
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                viewModel.create(name) { id -> onOpenAlbum(AlbumTarget.User(id)) }
            },
        )
    }
}

/**
 * The name prompt, shared by create and rename.
 *
 * Confirm stays disabled while the field is blank rather than explaining afterwards why nothing happened:
 * the repository will refuse a blank name anyway, so a dialog that let the user press it would be
 * showing a button that is known not to work.
 */
@Composable
fun AlbumNameDialog(
    title: Int,
    confirm: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    initial: String = "",
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.album_name_label)) },
                singleLine = true,
                isError = value.isBlank(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text(stringResource(confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) }
        },
    )
}

@Composable
private fun SectionHeader(@StringRes label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp),
    )
}

private val CardMinSize = 150.dp
private val CardGap = 10.dp
private val SectionPadding = 12.dp

package com.lumovault.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.lumovault.app.ui.screens.albums.AlbumNameDialog
import com.lumovault.app.ui.screens.albums.icon
import com.lumovault.app.ui.screens.albums.titleRes
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs

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
        LazyVerticalGrid(
            columns = GridCells.Fixed(AlbumColumns),
            state = rememberLazyGridState(),
            contentPadding = PaddingValues(horizontal = SectionPadding, vertical = SpaceSm),
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

            if (state.localFolders.isNotEmpty()) {
                item(key = "folders-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(R.string.albums_section_folders)
                }

                val repeatedNames = state.localFolders.groupingBy { it.displayName }.eachCount()

                state.localFolders.forEach { folder ->
                    item(key = "folder-${folder.relativePath}") {
                        val count = pluralStringResource(
                            R.plurals.album_items_count,
                            folder.mediaCount,
                            folder.mediaCount,
                        )
                        AlbumCard(
                            title = folder.displayName,
                            // The parent is only worth a place on the card when two folders share a name;
                            // otherwise it is a path, and a path is not what the album is called.
                            subtitle = if (repeatedNames.getValue(folder.displayName) > 1 &&
                                folder.parentLabel.isNotBlank()
                            ) {
                                "$count · ${folder.parentLabel}"
                            } else {
                                count
                            },
                            coverUri = folder.coverUri,
                            onClick = { onOpenAlbum(AlbumTarget.LocalFolder(folder.relativePath)) },
                        )
                    }
                }
            }

            item(key = "user-header", span = { GridItemSpan(maxLineSpan) }) {
                // The action moved here from a full-width button at the head of the screen. Two reasons: a
                // stretched primary button above everything made "make an album" the loudest claim on a screen
                // whose subject is the albums that already exist, and it spent 56 dp of a phone's height on a
                // control used once in a blue moon. It sits beside the heading of the section it adds to now,
                // which is where a user looks for it.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionHeader(
                        label = R.string.albums_section_user,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { creating = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.padding(end = SpaceXs),
                        )
                        Text(stringResource(R.string.albums_create_action))
                    }
                }
            }

            if (state.userAlbums.isEmpty()) {
                item(key = "user-empty", span = { GridItemSpan(maxLineSpan) }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = SpaceSm),
                        verticalArrangement = Arrangement.spacedBy(SpaceXs),
                    ) {
                        Text(
                            text = stringResource(R.string.albums_empty_title),
                            style = LumoVaultType.itemTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.albums_empty_body),
                            style = LumoVaultType.sectionDetail,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

@Composable
private fun SectionHeader(@StringRes label: Int, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(label),
        style = LumoVaultType.sectionHeader,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = SpaceSm, bottom = SpaceXs),
    )
}

private val AlbumColumns = 2
private val CardGap = 12.dp
private val SectionPadding = 12.dp

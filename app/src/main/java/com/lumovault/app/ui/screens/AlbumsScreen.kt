package com.lumovault.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.ui.components.PlaceholderScreen

@Composable
fun AlbumsScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_albums),
        description = stringResource(R.string.albums_placeholder),
        icon = Icons.Filled.PhotoAlbum,
        modifier = modifier,
    )
}

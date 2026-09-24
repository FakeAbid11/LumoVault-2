package com.lumovault.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.ui.components.PlaceholderScreen

@Composable
fun PhotosScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_photos),
        description = stringResource(R.string.photos_placeholder),
        icon = Icons.Filled.Photo,
        modifier = modifier,
    )
}

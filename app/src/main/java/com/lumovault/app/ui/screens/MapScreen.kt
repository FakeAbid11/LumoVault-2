package com.lumovault.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.ui.components.PlaceholderScreen

@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_map),
        description = stringResource(R.string.map_placeholder),
        icon = Icons.Filled.Map,
        modifier = modifier,
    )
}

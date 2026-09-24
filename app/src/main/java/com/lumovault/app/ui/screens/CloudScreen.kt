package com.lumovault.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.app.R
import com.lumovault.app.ui.components.PlaceholderScreen

@Composable
fun CloudScreen(modifier: Modifier = Modifier) {
    PlaceholderScreen(
        title = stringResource(R.string.nav_cloud),
        description = stringResource(R.string.cloud_placeholder),
        icon = Icons.Filled.Cloud,
        modifier = modifier,
    )
}

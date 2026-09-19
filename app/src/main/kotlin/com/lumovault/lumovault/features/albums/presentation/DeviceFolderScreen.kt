package com.lumovault.lumovault.features.albums.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.presentation.EmptyCollection
import com.lumovault.lumovault.features.gallery.presentation.MediaGrid

/**
 * One device folder's (MediaStore bucket's) contents.
 *
 * Ported from the device-folder branch of album_detail_screen.dart. The route
 * carries the bucket id; the ViewModel resolves it to the bucket name and
 * streams the matching Room rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceFolderScreen(
    folderId: String,
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: DeviceFolderViewModel = hiltViewModel(),
) {
    LaunchedEffect(folderId) { viewModel.open(folderId) }

    val name by viewModel.folderName.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(name ?: stringResource(R.string.albums_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
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
                modifier = Modifier.padding(padding),
            )
        }
    }
}
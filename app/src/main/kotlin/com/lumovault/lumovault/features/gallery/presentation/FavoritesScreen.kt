package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

/**
 * The favorites collection.
 *
 * Ported from lib/features/gallery/presentation/screens/favorites_screen.dart.
 * A favorite is a boolean column on the media row — there is no favorites
 * table — and the query excludes hidden and trashed items, so unfavoriting a
 * photo from here and hiding it elsewhere cannot leave a stale heart behind.
 *
 * Long-press removes the favorite rather than opening it, matching the
 * original's per-tile unfavorite action: in this view the user's intent is
 * almost always "I don't want this starred anymore".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: MediaCollectionsViewModel = hiltViewModel(),
) {
    val items by viewModel.favorites.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyCollection(
                title = stringResource(R.string.favorites_empty_title),
                explanation = stringResource(R.string.favorites_empty_explanation),
                modifier = Modifier.padding(padding),
            )
        } else {
            MediaGrid(
                items = items,
                onItemClick = { index -> onOpenItem(index, items) },
                onItemLongClick = { localId -> viewModel.toggleFavorite(localId, false) },
                modifier = Modifier.padding(padding).fillMaxSize(),
            )
        }
    }
}

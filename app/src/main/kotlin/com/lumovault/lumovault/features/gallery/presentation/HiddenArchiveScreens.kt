package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

/**
 * The hidden-items view.
 *
 * Ported from lib/features/hidden/presentation/screens/hidden_album_screen.dart.
 * Hidden is a Room flag, not an encrypted store: it removes a photo from the
 * main timeline, nothing more. The Flutter app's own comment notes the album is
 * not individually encrypted — the app lock covers the whole app instead — and
 * that boundary is preserved here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenScreen(
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: MediaCollectionsViewModel = hiltViewModel(),
) {
    val items by viewModel.hidden.collectAsStateWithLifecycle()

    FlagCollectionScaffold(
        title = stringResource(R.string.hidden_title),
        emptyTitle = stringResource(R.string.hidden_empty_title),
        emptyExplanation = stringResource(R.string.hidden_empty_explanation),
        items = items,
        onBack = onBack,
        onOpenItem = onOpenItem,
        onUnflag = { viewModel.setHidden(it, false) },
    )
}

/**
 * The archive view.
 *
 * Ported from lib/features/archive/presentation/screens/archive_screen.dart — a
 * structural twin of Hidden: another Room flag, another filtered grid, another
 * "put it back" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    viewModel: MediaCollectionsViewModel = hiltViewModel(),
) {
    val items by viewModel.archived.collectAsStateWithLifecycle()

    FlagCollectionScaffold(
        title = stringResource(R.string.archive_title),
        emptyTitle = stringResource(R.string.archive_empty_title),
        emptyExplanation = stringResource(R.string.archive_empty_explanation),
        items = items,
        onBack = onBack,
        onOpenItem = onOpenItem,
        onUnflag = { viewModel.setArchived(it, false) },
    )
}

/**
 * Shared chrome for the flag-filtered views.
 *
 * Hidden and Archive differ only in which column they read and which flag they
 * clear, so the scaffold is written once. Both take an unflag action in the
 * app bar rather than per-tile: the user's intent in these views is almost
 * always "put this back where I found it".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FlagCollectionScaffold(
    title: String,
    emptyTitle: String,
    emptyExplanation: String,
    items: List<com.lumovault.lumovault.core.database.entity.MediaItemEntity>,
    onBack: () -> Unit,
    onOpenItem: (Int, List<MediaItemEntity>) -> Unit,
    onUnflag: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = { items.forEach { onUnflag(it.localId) } }) {
                        Icon(Icons.Default.Visibility, contentDescription = stringResource(R.string.action_unhide_all))
                    }
                },
            )
        },
    ) { padding ->
        if (items.isEmpty()) {
            EmptyCollection(
                title = emptyTitle,
                explanation = emptyExplanation,
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

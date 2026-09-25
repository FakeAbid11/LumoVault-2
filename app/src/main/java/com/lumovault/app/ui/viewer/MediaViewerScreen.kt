package com.lumovault.app.ui.viewer

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.navigation.ViewerTarget
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lumovault.app.ui.theme.MediaBadgeScrim
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.ui.theme.FavoriteAccent
import com.lumovault.app.ui.theme.BackedUpAccent

/**
 * The full-screen viewer: one item, the list it came from, and what can be done to it.
 *
 * Chrome is drawn over the media and vanishes on a tap, because the photograph is the screen and the controls
 * are borrowed from it for a moment. The bottom row is the one the PRD's panel sketch names — heart, backup,
 * information — and the overflow carries the Phase 7 organisation actions, reached through the same
 * repositories the grids use. There is no second copy of that behaviour here, and no button on this screen can
 * upload something that was not asked to be uploaded.
 *
 * It is a route rather than an overlay for the reason album detail is one: the media id and its source are what
 * survive process death, and an overlay held in `remember` would lose both and reopen as a black screen with
 * nowhere to go back to.
 */
@Composable
fun MediaViewerScreen(
    mediaStoreId: Long,
    target: ViewerTarget,
    onNavigateUp: () -> Unit,
    onOpenMap: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaViewerViewModel = viewModel(),
) {
    ImmersiveViewer()

    LaunchedEffect(mediaStoreId, target) { viewModel.open(mediaStoreId, target) }

    val listing by viewModel.listing.collectAsStateWithLifecycle()

    when (val state = listing) {
        // Room has not answered about this item's list yet. Drawing nothing is the honest frame: a spinner
        // over black would look broken for one frame on every single open.
        Listing.Loading -> Unit

        // The list came back and does not hold the tapped photo. Say so, rather than opening some other
        // photograph at index zero — which is the failure a pager with a fallback index always has.
        is Listing.Missing -> PlaceholderScreen(
            title = stringResource(R.string.viewer_gone_title),
            description = stringResource(
                if (state.listSize == 0) R.string.viewer_gone_body_empty else R.string.viewer_gone_body
            ),
            icon = Icons.Filled.Close,
            modifier = modifier.fillMaxSize(),
        )

        is Listing.Ready -> ViewerPager(
            state = state,
            modifier = modifier,
            viewModel = viewModel,
            onNavigateUp = onNavigateUp,
            onOpenMap = onOpenMap,
        )
    }
}

/**
 * The pager, which exists only once there is a list to page through.
 *
 * `rememberPagerState` is called here rather than at the top of the screen because the page it must open on is
 * part of [Listing]: created earlier, it would capture an index from before the list that defines it had
 * arrived, and the two would then disagree for a frame on every open.
 */
@Composable
private fun ViewerPager(
    state: Listing.Ready,
    modifier: Modifier,
    viewModel: MediaViewerViewModel,
    onNavigateUp: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val items = state.items
    val pagerState = rememberPagerState(initialPage = state.initialPage, pageCount = { items.size })

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val backupState by viewModel.currentBackupState.collectAsStateWithLifecycle()
    val favorite by viewModel.currentFavorite.collectAsStateWithLifecycle()
    val metadata by viewModel.currentMetadata.collectAsStateWithLifecycle()
    val albums by viewModel.userAlbums.collectAsStateWithLifecycle()

    var chromeVisible by remember { mutableStateOf(true) }
    var showingDetails by remember { mutableStateOf(false) }
    var choosingAlbum by remember { mutableStateOf(false) }
    var confirmingTrash by remember { mutableStateOf(false) }
    var zoomedPage by remember { mutableStateOf<Int?>(null) }

    // The pager owns "which item". Reading the settled page back is what keeps the action row, the details
    // sheet and the on-demand EXIF read from ever disagreeing with the picture on screen.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page -> viewModel.pageSettled(page) }
    }

    // Widens the window from behind, the way the grids do it, so swiping to the end is not a wall.
    LaunchedEffect(pagerState, items.size) {
        snapshotFlow { pagerState.settledPage }
            .collect { page -> if (page >= items.size - LOAD_AHEAD) viewModel.loadMore() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            // A zoomed image pans under the finger. The two gestures share the horizontal axis, and this is the
            // single line that decides which of them a drag belongs to.
            userScrollEnabled = zoomedPage != pagerState.settledPage,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            ViewerPage(
                media = item,
                isCurrentPage = page == pagerState.settledPage,
                onTap = { chromeVisible = !chromeVisible },
                onZoomChanged = { zoomed -> zoomedPage = if (zoomed) page else null },
            )
        }

        if (chromeVisible) {
            val shown = currentItem
            ViewerTopBar(
                item = shown,
                position = pagerState.settledPage + 1,
                total = items.size,
                onNavigateUp = onNavigateUp,
                onArchive = viewModel::archiveCurrent,
                onTrash = { confirmingTrash = true },
                onAddToAlbum = albums.takeIf { it.isNotEmpty() }?.let { { choosingAlbum = true } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .systemBarsPadding(),
            )

            ViewerActionBar(
                media = shown,
                action = ViewerPresentation.backupAction(backupState),
                isFavorite = favorite,
                onBackUp = viewModel::backUpCurrent,
                onRetry = viewModel::retryCurrent,
                onFavorite = { viewModel.setFavorite(!favorite) },
                onInfo = { showingDetails = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .systemBarsPadding(),
            )
        }
    }

    val shown = currentItem
    if (showingDetails && shown != null) {
        MediaDetailsSheet(
            media = shown,
            metadata = metadata,
            onDismiss = { showingDetails = false },
            onOpenMap = if (ViewerPresentation.canShowOnMap(metadata)) {
                {
                    showingDetails = false
                    viewModel.requestMapFocus()
                    onOpenMap()
                }
            } else {
                null
            },
        )
    }

    if (choosingAlbum) {
        AlbumChooserDialog(
            albums = albums.map { album -> album.id to album.name },
            onDismiss = { choosingAlbum = false },
            onChoose = { albumId ->
                choosingAlbum = false
                viewModel.addToAlbum(albumId)
            },
        )
    }

    if (confirmingTrash) {
        AlertDialog(
            onDismissRequest = { confirmingTrash = false },
            title = { Text(stringResource(R.string.viewer_trash_title)) },
            text = { Text(stringResource(R.string.viewer_trash_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingTrash = false
                        viewModel.moveToTrashCurrent()
                    },
                ) { Text(stringResource(R.string.organization_trash_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingTrash = false }) {
                    Text(stringResource(R.string.album_cancel))
                }
            },
        )
    }
}

/** Which renderer a page gets, and nothing else. */
@Composable
private fun ViewerPage(
    media: Media,
    isCurrentPage: Boolean,
    onTap: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    when (ViewerPresentation.rendererFor(media.type)) {
        // Only the visible page holds a player, which is what keeps a swipe from leaving three decoders
        // running. The neighbours are not blank: they show a frame through the image path until they are current.
        ViewerRenderer.Video -> VideoStage(
            contentUri = media.contentUri,
            isActive = isCurrentPage,
            onTap = onTap,
        )

        ViewerRenderer.ZoomableImage, ViewerRenderer.AnimatedImage -> ZoomableImage(
            contentUri = media.contentUri,
            contentDescription = media.displayName,
            onTap = onTap,
            onZoomChanged = onZoomChanged,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ViewerTopBar(
    item: Media?,
    position: Int,
    total: Int,
    onNavigateUp: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onAddToAlbum: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onNavigateUp) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.viewer_close),
                tint = OnMedia,
            )
        }

        Text(
            // The capture time when the file carries one, otherwise its name — never the day it was added,
            // which is a fact about the device that would read as a fact about the moment.
            text = item?.let { media ->
                media.dateTakenSeconds?.let { taken -> dateTime.format(Date(taken * 1000L)) }
                    ?: media.displayName
            }.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            color = OnMedia,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        )

        if (total > 1) {
            Text(
                text = pluralStringResource(R.plurals.viewer_position, position, position, total),
                style = MaterialTheme.typography.labelMedium,
                color = OnMedia,
                modifier = Modifier.padding(end = 4.dp),
            )
        }

        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.viewer_more_actions),
                    tint = OnMedia,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (onAddToAlbum != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.viewer_add_to_album)) },
                        onClick = {
                            menuOpen = false
                            onAddToAlbum()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.organization_archive_action)) },
                    onClick = {
                        menuOpen = false
                        onArchive()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.organization_trash_action)) },
                    onClick = {
                        menuOpen = false
                        onTrash()
                    },
                )
            }
        }
    }
}

/**
 * The row under the media.
 *
 * The backup control is labelled by its state rather than drawn as a bare glyph: PRD section 13's panel is the
 * model, and a tick a person cannot read as "nothing is owed" is worse than no tick. While the queue is working
 * the control stays visible and disabled rather than vanishing, because a button that disappears mid-upload
 * looks like the upload was cancelled.
 */
@Composable
private fun ViewerActionBar(
    media: Media?,
    action: ViewerBackupAction,
    isFavorite: Boolean,
    onBackUp: () -> Unit,
    onRetry: () -> Unit,
    onFavorite: () -> Unit,
    onInfo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media == null) return
    val status = action.status
    val enabled = action is ViewerBackupAction.Show && action.enabled

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(10.dp),
        shape = RoundedCornerShape(16.dp),
        color = MediaBadgeScrim,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.organization_unfavorite_action
                        else R.string.organization_favorite_action,
                    ),
                    tint = if (isFavorite) FavoriteAccent else OnMedia,
                )
            }

            Box(modifier = Modifier.weight(1f))

            IconButton(
                onClick = if (status == ViewerBackupStatus.Failed) onRetry else onBackUp,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = ViewerFormatting.backupIcon(status),
                    contentDescription = null,
                    tint = if (status == ViewerBackupStatus.BackedUp) BackedUpAccent else OnMedia,
                )
            }
            Text(
                text = stringResource(ViewerFormatting.backupLabel(status)),
                style = MaterialTheme.typography.labelLarge,
                color = OnMedia,
            )

            Box(modifier = Modifier.weight(1f))

            IconButton(onClick = onInfo) {
                Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.viewer_info),
                    tint = OnMedia,
                )
            }
        }
    }
}

@Composable
private fun AlbumChooserDialog(
    albums: List<Pair<Long, String>>,
    onDismiss: () -> Unit,
    onChoose: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_add_to_album)) },
        text = {
            Column {
                albums.forEach { (albumId, name) ->
                    TextButton(
                        onClick = { onChoose(albumId) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = name,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_cancel)) }
        },
    )
}

/**
 * What the file knows about itself.
 *
 * The rows come from [ViewerPresentation.detailFields], which is where the presence decisions live, and each is
 * drawn only when it has something to say. Location is the one exception, and it is on the sheet because the
 * map is: someone deciding whether a photograph ever had a GPS fix needs to see that the question was asked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDetailsSheet(
    media: Media,
    metadata: MediaMetadata?,
    onDismiss: () -> Unit,
    onOpenMap: (() -> Unit)?,
) {
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.viewer_details_title),
                style = MaterialTheme.typography.titleMedium,
            )

            ViewerPresentation.detailFields(media, metadata).forEach { field ->
                val value = ViewerFormatting.fieldValue(field, media, metadata, dateTime)
                val missing = ViewerFormatting.fieldMissing(field)
                if (value != null || missing != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(ViewerFormatting.fieldLabel(field)),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value ?: stringResource(requireNotNull(missing)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (onOpenMap != null) {
                TextButton(onClick = onOpenMap) { Text(stringResource(R.string.viewer_open_map)) }
            }
        }
    }
}

/**
 * Hides the system bars while the viewer is on screen, and puts them back when it leaves.
 *
 * The framework API rather than a compatibility wrapper, because the wrapper is not a declared dependency of
 * this module and adding one for four calls is not worth it: API 30 has `WindowInsetsController`, and API 29 —
 * this app's floor — spells the same request with the deprecated `systemUiVisibility` flags. The deprecated
 * branch is deliberate and lives nowhere else, which is the only thing that would make a wrapper worth it.
 */
@Composable
private fun ImmersiveViewer() {
    val context = LocalContext.current
    val window = remember(context) { (context as? Activity)?.window }

    DisposableEffect(window) {
        hideSystemBars(window)
        onDispose { showSystemBars(window) }
    }
}

private fun hideSystemBars(window: Window?) {
    if (window == null) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
}

private fun showSystemBars(window: Window?) {
    if (window == null) return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        window.insetsController?.show(WindowInsets.Type.systemBars())
    } else {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }
}

/** Pages still to load before the pager would reach the end of what Room has handed over. */
private const val LOAD_AHEAD = 6


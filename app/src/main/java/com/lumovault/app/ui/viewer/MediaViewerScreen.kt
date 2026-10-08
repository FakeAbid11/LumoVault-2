package com.lumovault.app.ui.viewer

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaMetadata
import com.lumovault.app.domain.model.ShareableMedia
import com.lumovault.app.ui.components.CollectAppMessages
import com.lumovault.app.ui.components.LoadingScreen
import com.lumovault.app.ui.components.MoreSheet
import com.lumovault.app.ui.components.MoreSheetDivider
import com.lumovault.app.ui.components.MoreSheetRow
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.navigation.ViewerTarget
import com.lumovault.app.util.MediaShare
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.flow.distinctUntilChanged
import com.lumovault.app.ui.theme.BackedUpAccent
import com.lumovault.app.ui.theme.ChromeFadeMillis
import com.lumovault.app.ui.theme.ChromeScrim
import com.lumovault.app.ui.theme.FavoriteAccent
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MediaBadgeScrim
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SpaceXxs
import com.lumovault.app.ui.theme.SyncingAccent

/**
 * The full-screen viewer: one item, the list it came from, and what can be done to it.
 *
 * Chrome is drawn over the media and vanishes on a tap, because the photograph is the screen and the controls
 * are borrowed from it for a moment. Two rows carry the actions: the top is the PRD's close-and-options bar,
 * and the bottom is the panel sketch's own four â€” heart, share, backup, information â€” with the overflow
 * beside them. Everything those four have no room for is one sheet under the thumb rather than a menu
 * hanging off the top-right corner, and every one of them reaches the same repositories the grids use. There
 * is no second copy of that behaviour here, and no button on this screen can upload something that was not
 * asked to be uploaded.
 *
 * It is a route rather than an overlay for the reason album detail is one: the media id and its source are
 * what survive process death, and an overlay held in `remember` would lose both and reopen as a black screen
 * with nowhere to go back to.
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

    // Failed writes from this screen â€” the heart that would not move, the trash that would not take â€”
    // shown by the shell's one snackbar over the black frame, collected before any branch routes
    // around it.
    CollectAppMessages(viewModel.messages)

    val listing by viewModel.listing.collectAsStateWithLifecycle()

    when (val state = listing) {
        // Room has not answered about this item's list yet. There is no picture to draw, so the frame stays
        // black rather than opening on nothing â€” but the control that leaves is drawn regardless, and the
        // theme's waiting ring is drawn with it. A screen that is both black and silent reads as a crash,
        // and "the query answers promptly" is not a promise this screen gets to assume.
        Listing.Loading -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            // The shared body, in the colour the rest of this screen already marks waiting with: the
            // scheme's own primary is legible on black but is not this surface's language, and two
            // different ring colours on one screen is a distinction with no meaning behind it.
            LoadingScreen(color = SyncingAccent)
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.align(Alignment.TopStart).padding(SpaceSm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.viewer_close),
                    tint = OnMedia,
                )
            }
        }

        // The list came back and does not hold the tapped photo. Say so, rather than opening some other
        // photograph at index zero â€” which is the failure a pager with a fallback index always has. The exit
        // is drawn for the same reason the Loading branch draws one: with the system bars hidden, a screen
        // whose only way out is an uninstructed gesture reads as a crash.
        is Listing.Missing -> Box(modifier = modifier.fillMaxSize()) {
            PlaceholderScreen(
                title = stringResource(R.string.viewer_gone_title),
                description = stringResource(
                    if (state.listSize == 0) R.string.viewer_gone_body_empty else R.string.viewer_gone_body
                ),
                icon = Icons.Filled.Close,
                modifier = Modifier.fillMaxSize(),
            )
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.align(Alignment.TopStart).padding(SpaceSm),
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.viewer_close),
                    tint = OnMedia,
                )
            }
        }

        is Listing.Ready -> ViewerPager(
            state = state,
            target = target,
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
    target: ViewerTarget,
    modifier: Modifier,
    viewModel: MediaViewerViewModel,
    onNavigateUp: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val items = state.items
    val context = LocalContext.current
    val pagerState = rememberPagerState(initialPage = state.initialPage, pageCount = { items.size })

    val currentItem by viewModel.currentItem.collectAsStateWithLifecycle()
    val backupState by viewModel.currentBackupState.collectAsStateWithLifecycle()
    val favorite by viewModel.currentFavorite.collectAsStateWithLifecycle()
    val metadata by viewModel.currentMetadata.collectAsStateWithLifecycle()
    val albums by viewModel.userAlbums.collectAsStateWithLifecycle()

    var chromeVisible by remember { mutableStateOf(true) }
    // Saveable, not remembered: the activity recreates on rotation and on theme toggle, and a sheet
    // the user was filling in â€” or a trash confirmation they were about to answer â€” is their context,
    // not the pixels'. These are plain Booleans, which is what the default saver can carry.
    var showingDetails by rememberSaveable { mutableStateOf(false) }
    var showingMore by rememberSaveable { mutableStateOf(false) }
    var choosingAlbum by rememberSaveable { mutableStateOf(false) }
    var confirmingTrash by rememberSaveable { mutableStateOf(false) }

    // Whether the cloud index itself names this list. The two facts the bar and the panel need both
    // start here: a photo reached from the Cloud tab is in the user's channel whatever the local queue
    // has to say about it, and saying so is the one thing that keeps that screen's badge and this
    // screen's backup control from contradicting each other on the same file.
    val fromCloudIndex = target == ViewerTarget.Cloud
    val backupAction = ViewerPresentation.backupAction(backupState, fromCloudIndex)
    val storage = ViewerPresentation.storageLocation(backupAction.status, fromCloudIndex)

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

    // The window can shrink under a pager that stayed composed â€” a retirement keeps this pager alive
    // now, and trashing the last item leaves the kept page one past the new end. Land on the new last.
    LaunchedEffect(pagerState, items.size) {
        if (pagerState.currentPage > items.size - 1) pagerState.scrollToPage(items.size - 1)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        HorizontalPager(
            state = pagerState,
            // Scrolling is left enabled unconditionally, which is the opposite of how this line used to read.
            // A page that knows its own zoom is the one that decides whether a horizontal drag is its business
            // â€” see the `canPan` gate in [ZoomableImage] â€” and a flag up here could only ever be a *copy* of
            // that fact, held by the wrong object: a page resets to 1Ã— without announcing it, and a stale copy
            // naming the page you are standing on turns every swipe into nothing.
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val item = items.getOrNull(page) ?: return@HorizontalPager
            ViewerPage(
                media = item,
                // `targetPage`, not `settledPage`: held to the settled page, the outgoing clip keeps
                // playing its audio for the whole swipe animation while the incoming one waits as a
                // silent poster â€” two pages are "current" for the length of a swipe and only one of
                // them is what the user is pointing at. (A modal does *not* go through this flag:
                // deactivating releases the player, and restarting a half-watched clip from zero to
                // close a details sheet is a worse interruption than hearing it under the sheet.)
                isCurrentPage = page == pagerState.targetPage,
                onTap = { chromeVisible = !chromeVisible },
                onClose = onNavigateUp,
            )
        }

        // The band arrives and leaves on a fade rather than a swap, and the container is one AnimatedVisibility
        // rather than three: a control strip that snaps makes the screen feel twitchy under the finger, and all
        // three parts are one decision the user made with a tap. The inner box is because the animation's content
        // is outside the screen box's scope â€” `align` still needs a Box to hang from â€” and because an ordinary
        // box passes a tap straight through to the pager, exactly as the old `if` did.
        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(ChromeFadeMillis)),
            exit = fadeOut(tween(ChromeFadeMillis)),
            modifier = Modifier.fillMaxSize(),
        ) {
            val shown = currentItem
            Box(modifier = Modifier.fillMaxSize()) {
                // A band, not a bar: the controls are white and a bright photograph is full of white, but the
                // picture is still the screen, so only the top of it is darkened â€” and only for as long as the
                // chrome is up.
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .systemBarsPadding()
                        .height(ChromeScrimHeight)
                        .background(
                            Brush.verticalGradient(listOf(ChromeScrim, Color.Transparent)),
                        ),
                )
                ViewerTopBar(
                    item = shown,
                    position = pagerState.settledPage + 1,
                    total = items.size,
                    onNavigateUp = onNavigateUp,
                    onMore = { showingMore = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .systemBarsPadding(),
                )

                ViewerActionBar(
                    media = shown,
                    action = backupAction,
                    isFavorite = favorite,
                    onBackUp = viewModel::backUpCurrent,
                    onRetry = viewModel::retryCurrent,
                    onFavorite = { viewModel.setFavorite(!favorite) },
                    onShare = {
                        shown?.let { media ->
                            MediaShare.share(
                                context,
                                listOf(ShareableMedia(uri = media.contentUri, mimeType = media.mimeType)),
                                // The sheet is not opened when there is nothing to send, and until this
                                // was wired the answer was simply dropped: a share button that opens
                                // nothing in silence reads as a broken button.
                                onEmpty = viewModel::reportUnshareable,
                            )
                        }
                    },
                    onInfo = { showingDetails = true },
                    onMore = { showingMore = true },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .systemBarsPadding(),
                )
            }
        }
    }

    val shown = currentItem
    if (showingDetails && shown != null) {
        MediaDetailsSheet(
            media = shown,
            metadata = metadata,
            backupStatus = backupAction.status,
            storage = storage,
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

    // The overflow lives in one place and is reached from either bar. It carries only what those two
    // rows have no room for â€” favourite, backup and information are already drawn, and a second control
    // for a decision the user can see is a menu nobody needs.
    if (showingMore) {
        ViewerMoreSheet(
            canAddToAlbum = albums.isNotEmpty(),
            onDismiss = { showingMore = false },
            onAddToAlbum = {
                showingMore = false
                choosingAlbum = true
            },
            onArchive = {
                showingMore = false
                viewModel.archiveCurrent()
            },
            onTrash = {
                showingMore = false
                confirmingTrash = true
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
    onClose: () -> Unit,
) {
    when (ViewerPresentation.rendererFor(media.type)) {
        // Only the visible page holds a player: the others draw the clip's own first frame and allocate
        // nothing, which is what keeps a swipe from leaving three decoders running behind the finger.
        ViewerRenderer.Video -> VideoStage(
            contentUri = media.contentUri,
            mediaStoreId = media.id,
            isActive = isCurrentPage,
            onTap = onTap,
            onClose = onClose,
        )

        ViewerRenderer.ZoomableImage, ViewerRenderer.AnimatedImage -> ZoomableImage(
            contentUri = media.contentUri,
            contentDescription = media.displayName,
            onTap = onTap,
        )
    }
}

/**
 * The bar across the top of the photograph: what it is, where in the list it sits, and the way out.
 *
 * Three controls and no more, which is the PRD's own count â€” close, the overflow, and nothing else. The
 * overflow is a sheet rather than the menu it used to be: a menu anchored to this corner draws over the
 * photograph at the top of a screen held out at arm's length, and its rows hang off an edge the thumb
 * cannot reach without re-gripping. The same actions, one gesture lower, are simply easier to hit.
 */
@Composable
private fun ViewerTopBar(
    item: Media?,
    position: Int,
    total: Int,
    onNavigateUp: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(SpaceXs),
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
            // The capture time when the file carries one, otherwise its name â€” never the day it was added,
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
                .padding(horizontal = SpaceSm),
        )

        if (total > 1) {
            Text(
                text = pluralStringResource(R.plurals.viewer_position, position, position, total),
                style = MaterialTheme.typography.labelMedium,
                color = OnMedia,
                modifier = Modifier.padding(end = SpaceXs),
            )
        }

        IconButton(onClick = onMore) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more_actions),
                tint = OnMedia,
            )
        }
    }
}

/**
 * The row under the media.
 *
 * Five controls in three groups: two to the left of the backup state, two to the right, and that state
 * between them â€” which is also what makes it land in the middle of the screen rather than a third of the
 * way across, as it did when the right-hand side held one glyph. The backup control is labelled by its
 * state rather than drawn as a bare glyph: PRD section 13's panel is the model, and a tick a person
 * cannot read as "nothing is owed" is worse than no tick. While the queue is working the control stays
 * visible and disabled rather than vanishing, because a button that disappears mid-upload looks like the
 * upload was cancelled. The overflow's own actions live one sheet below rather than here, because these
 * five are the ones the panel sketch names and the rest would not fit without a second row.
 */
@Composable
private fun ViewerActionBar(
    media: Media?,
    action: ViewerBackupAction,
    isFavorite: Boolean,
    onBackUp: () -> Unit,
    onRetry: () -> Unit,
    onFavorite: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media == null) return
    val status = action.status
    val enabled = action is ViewerBackupAction.Show && action.enabled

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(SpaceMd),
        shape = MaterialTheme.shapes.large,
        color = MediaBadgeScrim,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = SpaceXs, vertical = SpaceXxs),
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

            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Filled.Share,
                    // The row's other controls name themselves for TalkBack the same way; an
                    // unlabelled share glyph would be the one button a screen reader cannot name.
                    contentDescription = stringResource(R.string.share_action),
                    tint = OnMedia,
                )
            }

            Box(modifier = Modifier.weight(1f))

            IconButton(
                onClick = if (status == ViewerBackupStatus.Failed) onRetry else onBackUp,
                enabled = enabled,
            ) {
                Icon(
                    imageVector = ViewerFormatting.backupIcon(status),
                    // The label the row draws beside this is the button's own name for TalkBack;
                    // without it the only backup control in the viewer is an anonymous button.
                    contentDescription = stringResource(ViewerFormatting.backupLabel(status)),
                    // An explicit tint bypasses the content alpha a disabled control gets by itself,
                    // so the dimming the "wait" state needs is spelled out: `Busy` disables the button
                    // and the icon must look disabled with it. Uploading leads because `Busy` is the
                    // only action that carries that status, and it is the transfer's own mark: brandSky.
                    tint = when {
                        status == ViewerBackupStatus.Uploading -> SyncingAccent.copy(alpha = DisabledContentAlpha)
                        !enabled -> OnMedia.copy(alpha = DisabledContentAlpha)
                        status == ViewerBackupStatus.BackedUp -> BackedUpAccent
                        else -> OnMedia
                    },
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

            // The row's own copy of the top bar's overflow. The two appear and vanish together, so this
            // is not a fallback for a hidden control â€” it is where the thumb already is. The actions
            // themselves are one sheet, defined once, reached from either.
            IconButton(onClick = onMore) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more_actions),
                    tint = OnMedia,
                )
            }
        }
    }
}

/**
 * Everything the two bars have no room for, as one sheet under the thumb.
 *
 * This replaced the `DropdownMenu` that hung off the top-right corner. A menu anchored there draws over
 * the photograph, at the top of a screen held out at arm's length, with rows that fall off the edge the
 * thumb is not on; a sheet arrives where the hand already is and gives every row a full-width target,
 * which is also what TalkBack reads best. It carries only the actions the bars do not: favourite, backup
 * and information are drawn, and a second control for a decision the user can see is a menu nobody needs.
 *
 * The divider *is* the grouping rather than a heading over each block. Two of these rows file the picture
 * and one hides it, and with three rows in total a heading above each would be taller than the actions it
 * names â€” the separation has to be visible without spending the space.
 */
@Composable
private fun ViewerMoreSheet(
    canAddToAlbum: Boolean,
    onDismiss: () -> Unit,
    onAddToAlbum: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
) {
    MoreSheet(title = R.string.more_actions, onDismiss = onDismiss) {
        if (canAddToAlbum) {
            MoreSheetRow(
                icon = Icons.Filled.Add,
                label = R.string.viewer_add_to_album,
                onClick = onAddToAlbum,
            )
        }
        MoreSheetRow(
            icon = Icons.Filled.Archive,
            label = R.string.organization_archive_action,
            onClick = onArchive,
        )

        MoreSheetDivider()

        MoreSheetRow(
            icon = Icons.Filled.Delete,
            label = R.string.organization_trash_action,
            onClick = onTrash,
        )
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
            // Scrollable and bounded: a library with forty albums currently renders forty rows and the
            // last few below the dialog's own bottom edge, unreachable â€” the list is the choice, so
            // the choice has to fit.
            Column(
                modifier = Modifier
                    .heightIn(max = AlbumChooserMaxHeight)
                    .verticalScroll(rememberScrollState()),
            ) {
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
 *
 * The last two rows are the two facts no file carries inside itself â€” whether it has been sent, and whether a
 * second copy exists anywhere else â€” and they are answered here rather than by [ViewerPresentation.detailFields]
 * because neither belongs to the file: one is the queue's record and one is the cloud index's, and the panel
 * would otherwise have to be handed a database to draw a line about a photograph.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaDetailsSheet(
    media: Media,
    metadata: MediaMetadata?,
    backupStatus: ViewerBackupStatus,
    storage: ViewerStorage,
    onDismiss: () -> Unit,
    onOpenMap: (() -> Unit)?,
) {
    val dateTime = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    // Resolved once, outside the row builder: these two are the panel's own answers rather than the file's,
    // and reading them here keeps every row below a plain label-and-value pair with no decision left in it.
    val backupRow = stringResource(ViewerFormatting.backupStatus(backupStatus))
    val storageRow = stringResource(ViewerFormatting.storageLabel(storage))

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpaceXl)
                .padding(bottom = SpaceXl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpaceMd),
        ) {
            Text(
                text = stringResource(R.string.viewer_details_title),
                style = LumoVaultType.sectionHeader,
                color = MaterialTheme.colorScheme.onSurface,
            )

            ViewerPresentation.detailFields(media, metadata).forEach { field ->
                val value = ViewerFormatting.fieldValue(field, media, metadata, dateTime)
                val missing = ViewerFormatting.fieldMissing(field)
                if (value != null || missing != null) {
                    FactRow(
                        label = ViewerFormatting.fieldLabel(field),
                        value = value ?: stringResource(requireNotNull(missing)),
                    )
                }
            }

            FactRow(label = R.string.backup_hub_section_backup, value = backupRow)
            FactRow(label = R.string.backup_hub_section_storage, value = storageRow)

            if (onOpenMap != null) {
                TextButton(
                    onClick = onOpenMap,
                    modifier = Modifier.padding(top = SpaceSm),
                ) { Text(stringResource(R.string.viewer_open_map)) }
            }
        }
    }
}

/**
 * One label/value pair of [MediaDetailsSheet].
 *
 * Label left, value right, one line each where it can go on one line. The rows used to be two `Text`s in a
 * row with the label taking half the width: a long camera name then wrapped under itself while a short
 * resolution sat alone on the right, and a list whose rows break at different points is a list you have to
 * re-read to parse. Extracted because the panel now draws rows from two sources and a component that exists
 * once cannot drift from the others.
 *
 * Named [FactRow] rather than `DetailRow` because it is a different component from the shared one of that
 * name: that row is a status line that puts a word or a chip at the far edge of a settings list, and this
 * one is a fixed two-column table whose label column is deliberately narrow. Sharing a name would have
 * made the two look interchangeable in a code review, which is how one of them eventually gets edited as
 * if it were the other.
 */
@Composable
private fun FactRow(@StringRes label: Int, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpaceMd),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = stringResource(label),
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(DetailLabelWidth),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Hides the system bars while the viewer is on screen, and puts them back when it leaves.
 *
 * The framework API rather than a compatibility wrapper, because the wrapper is not a declared dependency of
 * this module and adding one for four calls is not worth it: API 30 has `WindowInsetsController`, and API 29 â€”
 * this app's floor â€” spells the same request with the deprecated `systemUiVisibility` flags. The deprecated
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

/**
 * How far down the top band of scrim reaches: the bar's own 64 dp plus the height of the tallest title it
 * carries, so the fade ends below the words rather than through the middle of them.
 */
private val ChromeScrimHeight = 104.dp

/** Material's own disabled content alpha, spelled out because the explicit tint bypasses the default. */
private const val DisabledContentAlpha = 0.38f

/** How much of the screen the album list may take before it scrolls rather than runs off. */
private val AlbumChooserMaxHeight = 360.dp

/** The metadata sheet's label column. Wide enough for "Exposure programme", narrow enough to leave a value room. */
private val DetailLabelWidth = 116.dp


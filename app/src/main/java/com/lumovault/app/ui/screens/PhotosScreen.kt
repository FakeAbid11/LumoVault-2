package com.lumovault.app.ui.screens

import android.content.Context
import com.lumovault.app.util.openAppDetailsSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.model.TimelineRail
import com.lumovault.app.ui.components.CollectAppMessages
import com.lumovault.app.ui.components.LoadingScreen
import com.lumovault.app.ui.components.MediaCell
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.components.WorkingScreen
import com.lumovault.app.ui.screens.photos.DateRail
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.screens.photos.BackupOverview
import com.lumovault.app.ui.components.ActionIcon
import com.lumovault.app.ui.components.SelectionBar
import com.lumovault.app.ui.screens.photos.PhotosViewModel
import com.lumovault.app.ui.screens.photos.PhotosUiState
import com.lumovault.app.util.DayDistance
import com.lumovault.app.util.dayDistance
import com.lumovault.app.util.formatDay
import com.lumovault.app.util.MediaShare
import java.time.LocalDate
import kotlinx.coroutines.launch
import com.lumovault.app.ui.theme.GridCellMinSize
import com.lumovault.app.ui.theme.GridSpacing
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MarkInline
import com.lumovault.app.ui.theme.RailWidth
import com.lumovault.app.ui.theme.RingStroke
import com.lumovault.app.ui.theme.SelectionBarInset
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SyncingAccent

/**
 * The local library. It reads one value — [PhotosUiState] — and draws that, so a combination the
 * screen cannot actually be in has nowhere to come from.
 *
 * No MediaStore query, database access or bitmap handling exists below this point.
 */
@Composable
fun PhotosScreen(
    onOpenMedia: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotosViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val backup by viewModel.backup.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // One context for the one share utility: the strip below never builds an intent itself.
    val context = LocalContext.current

    // Failures of the bar's writes and the counts of its bulk successes, shown by the shell's one
    // snackbar. Collected before any state can route around it, so a line is never lost to a branch.
    CollectAppMessages(viewModel.messages)

    // Trashing a selection and withdrawing the queue are both bulk acts with no undo from the bar, so
    // both confirm — the viewer already confirms the same trash action for one item.
    var confirmingTrash by remember { mutableStateOf(false) }
    var confirmingCancel by remember { mutableStateOf(false) }

    // Photos is the root tab: back with the selection bar up used to leave the app mid-gesture.
    // Back now means "stop selecting", which is what every other gallery does.
    BackHandler(enabled = selected.isNotEmpty()) { viewModel.clearSelection() }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshAccess() }

    /**
     * Re-read the grant and rescan on every resume: access can be revoked from system settings
     * while the app is backgrounded, and the camera writes new files outside the app's knowledge.
     */
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val isRefreshing = (state as? PhotosUiState.Content)?.isRefreshing == true

    // One condition, read twice. The strip at the bottom of the screen and the gap the grid leaves for it
    // cannot be decided in two places, or the last row of somebody's photos ends up under a button — which is
    // not a cosmetic miss, because the cell that is covered is the one they were trying to tap.
    val showBar = selected.isNotEmpty() ||
        backup.summary.isActive ||
        backup.summary.failed > 0
    val bottomInset = if (showBar) SelectionBarInset else GridSpacing

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when (val current = state) {
            // The first permission read has not arrived yet. The ring claims only that a question is
            // pending — where silence left a blank frame, and where "no photos" would flash a lie on
            // a device that has thousands.
            PhotosUiState.CheckingAccess -> LoadingScreen()

            // Room has not said how many rows there are. The same ring as above, because it is the same
            // claim — a question is pending — and deliberately not [PhotosUiState.Empty], which would be
            // a second, different answer to "why is nothing on screen".
            PhotosUiState.CheckingIndex -> LoadingScreen()

            PhotosUiState.PermissionRequired -> PermissionRequired(
                onRequestAccess = {
                    permissionLauncher.launch(viewModel.mediaPermissionsToRequest().toTypedArray())
                },
            )

            // How many rows have actually been indexed, not a percentage: a scan cannot know its
            // total before it has finished, and inventing one would be a number the user watches
            // stall. The count is passed as a fact this screen already holds.
            PhotosUiState.Scanning -> WorkingScreen(
                title = stringResource(R.string.photos_scanning_title),
                detail = pluralStringResource(
                    R.plurals.photos_scanning_progress,
                    scanProgress,
                    scanProgress,
                ),
            )

            PhotosUiState.Empty -> PlaceholderScreen(
                title = stringResource(R.string.photos_empty_title),
                description = stringResource(R.string.photos_empty_body),
                icon = Icons.Filled.PhotoLibrary,
                modifier = Modifier.fillMaxSize(),
            )

            is PhotosUiState.Failure -> LibraryUnavailable(onRetry = viewModel::refresh)

            is PhotosUiState.Content -> Timeline(
                state = current,
                backup = backup,
                favoriteIds = favorites,
                selected = selected,
                bottomInset = bottomInset,
                // One tap opens, one long press selects, and after that every tap adds to the selection.
                // The rule is decided here rather than in the model because it is a fact about the touch,
                // not about the data: the same id means different things depending on what is already chosen.
                onCellClick = { mediaId ->
                    if (selected.isEmpty()) onOpenMedia(mediaId) else viewModel.onCellClick(mediaId)
                },
                onCellLongClick = viewModel::onCellLongClick,
                onLoadMore = viewModel::loadMore,
            )
        }

        // The selection bar and the queue's progress line are the same strip: a user who has just
        // chosen items is about to see them queued, and swapping one control for the other in place
        // means the screen does not jump.
        if (showBar) {
            BackupBar(
                selectionSize = selected.size,
                summary = backup.summary,
                onSelectAll = viewModel::selectAll,
                onShare = { viewModel.shareSelected { items -> MediaShare.share(context, items) } },
                onBackUp = viewModel::backUpSelected,
                onFavorite = { viewModel.setFavoriteSelected(true) },
                onArchive = viewModel::archiveSelected,
                onTrash = { confirmingTrash = true },
                onClear = viewModel::clearSelection,
                onCancel = { confirmingCancel = true },
                onRetryFailed = viewModel::retryFailed,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            )
        }
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
                        viewModel.moveToTrashSelected()
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

    if (confirmingCancel) {
        AlertDialog(
            onDismissRequest = { confirmingCancel = false },
            title = { Text(stringResource(R.string.backup_cancel_confirm_title)) },
            text = { Text(stringResource(R.string.backup_cancel_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingCancel = false
                        viewModel.cancelPending()
                    },
                ) { Text(stringResource(R.string.backup_cancel)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingCancel = false }) {
                    Text(stringResource(R.string.album_cancel))
                }
            },
        )
    }
}

/**
 * Selection actions and queue progress, in one low strip.
 *
 * Only drawn when there is something to say — the caller decides, and decides the grid's bottom inset from
 * the same answer. A permanent "0 items to back up" bar would be the loudest thing on the screen and would
 * say nothing, and a bar that floats over the last row of photos hides the cell the user was reaching for.
 * All three faces are the shared [SelectionBar]: same surface, same rhythm, so the strip does not swap
 * shape under the user as its news changes.
 *
 * The actions are icons rather than words so that they and the count fit one phone width before the row
 * needs a scroll: the same marks the cell itself uses when a single photo is chosen, so the strip repeats
 * a vocabulary the thumbnail has already taught instead of naming it again in text. Every one of them is
 * labelled for TalkBack by its content description.
 */
@Composable
private fun BackupBar(
    selectionSize: Int,
    summary: BackupQueueSummary,
    onSelectAll: () -> Unit,
    onShare: () -> Unit,
    onBackUp: () -> Unit,
    onFavorite: () -> Unit,
    onArchive: () -> Unit,
    onTrash: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onRetryFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        selectionSize > 0 -> SelectionBar(
            label = pluralStringResource(R.plurals.selected_count, selectionSize, selectionSize),
            modifier = modifier.padding(SpaceSm),
        ) {
            // Select all speaks for the selection itself, so it leads; Share comes next because it
            // hands the same selection elsewhere without changing it. Then the organisation marks:
            // favourite, archive and Trash are decisions about the library; "Back Up" is the one that
            // costs the user data, so it sits at the end where the thumb finishes. They share a strip
            // because they act on the same selection.
            ActionIcon(Icons.Filled.SelectAll, R.string.selection_select_all, onSelectAll)
            ActionIcon(Icons.Filled.Share, R.string.share_action, onShare)
            ActionIcon(Icons.Filled.FavoriteBorder, R.string.organization_favorite_action, onFavorite)
            ActionIcon(Icons.Filled.Archive, R.string.organization_archive_action, onArchive)
            ActionIcon(Icons.Filled.Delete, R.string.organization_trash_action, onTrash)
            ActionIcon(Icons.Filled.Close, R.string.selection_clear, onClear)
            Button(onClick = onBackUp) {
                Text(stringResource(R.string.backup_action))
            }
        }

        summary.isActive -> SelectionBar(
            // Counts, not a percentage: the queue knows how many items exist and how many are
            // done, and a percentage of a queue that can grow mid-run would be a guess.
            label = stringResource(
                R.string.backup_progress_count,
                summary.backedUp + summary.failed + 1,
                summary.total,
            ),
            modifier = modifier.padding(SpaceSm),
        ) {
            if (summary.inFlight > 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(MarkInline),
                    color = SyncingAccent,
                    strokeWidth = RingStroke,
                )
            }
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.backup_cancel))
            }
        }

        else -> SelectionBar(
            label = pluralStringResource(
                R.plurals.backup_failed_count,
                summary.failed,
                summary.failed,
            ),
            modifier = modifier.padding(SpaceSm),
        ) {
            TextButton(onClick = onRetryFailed) {
                Text(stringResource(R.string.backup_retry_failed))
            }
        }
    }
}

@Composable
private fun Timeline(
    state: PhotosUiState.Content,
    backup: BackupOverview,
    favoriteIds: Set<Long>,
    selected: Set<Long>,
    bottomInset: Dp,
    onCellClick: (Long) -> Unit,
    onCellLongClick: (Long) -> Unit,
    onLoadMore: () -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scrollScope = rememberCoroutineScope()
    val renderedRows = state.days.sumOf { it.items.size } + state.days.size +
        if (state.limitedAccess) 1 else 0

    /**
     * The rail's shape: one entry per month, each knowing where it starts in the grid.
     *
     * Derived from the days that are already in memory for the grid itself, so the scrubber adds no query, no
     * second copy of the library and no work proportional to the photos — a ten-year library is a hundred and
     * twenty months, not a hundred and twenty thousand rows. The limited-access notice above the timeline is a
     * rendered item too, and passing its index in is what keeps every month pointing at the row the grid will
     * actually find it on.
     */
    val railMonths = remember(state.days, state.limitedAccess) {
        TimelineRail.months(state.days, firstItemIndex = if (state.limitedAccess) 1 else 0)
    }

    // Reading the first visible index is what makes the highlight follow a scroll — but inside
    // `derivedStateOf`, not directly in composition: the raw index changes with every scrolled item,
    // while the month it answers for usually does not. Derived, a scroll within one month recomposes
    // nothing; the dedup is by the month's own equality.
    val activeMonth by remember(railMonths) {
        derivedStateOf { TimelineRail.monthFor(railMonths, gridState.firstVisibleItemIndex) }
    }

    LaunchedEffect(gridState, renderedRows, state.hasMoreToLoad) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (state.hasMoreToLoad && lastVisible >= renderedRows - LOAD_AHEAD) {
                    onLoadMore()
                }
            }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Said above the grid rather than as a grid item: the rail indexes count grid rows, and a
        // notice that is not one must not shift every month's pointer. The outline tone keeps it
        // quiet — the rows below are still true, they are just from the last scan that finished.
        if (state.refreshFailed) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = GridSpacing, end = GridSpacing + RailWidth, top = SpaceSm),
            ) {
                StatusPill(
                    text = stringResource(R.string.photos_refresh_failed),
                    tone = PillTone.Missing,
                )
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = GridCellMinSize),
                state = gridState,
                // The rail's width is reserved beside the grid rather than drawn over it: a scrubber that covers
                // the rightmost column of photos has taken the thing it is there to help the user reach.
                contentPadding = PaddingValues(
                    start = GridSpacing,
                    top = GridSpacing,
                    end = GridSpacing + RailWidth,
                    bottom = bottomInset,
                ),
                horizontalArrangement = Arrangement.spacedBy(GridSpacing),
                verticalArrangement = Arrangement.spacedBy(GridSpacing),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (state.limitedAccess) {
                    item(key = "limited-access", span = { GridItemSpan(maxLineSpan) }) {
                        LimitedAccessNotice(modifier = Modifier.animateItem())
                    }
                }

                state.days.forEach { day ->
                    item(key = "day-${day.epochDay}", span = { GridItemSpan(maxLineSpan) }) {
                        DayHeader(epochDay = day.epochDay, modifier = Modifier.animateItem())
                    }
                    items(items = day.items, key = { media -> media.id }) { media ->
                        MediaCell(
                            media = media,
                            modifier = Modifier.animateItem(),
                            status = backup.statusOf(media.id),
                            favorite = media.id in favoriteIds,
                            selected = media.id in selected,
                            onClick = { onCellClick(media.id) },
                            onLongClick = { onCellLongClick(media.id) },
                        )
                    }
                }
            }

            DateRail(
                months = railMonths,
                activeMonth = activeMonth,
                onScrub = { index ->
                    // A jump, not an animation. A drag delivers a position per frame, and an animation per frame
                    // is a queue of flights the finger keeps interrupting — which reads as lag, then as a stuck
                    // rail. `scrollToItem` lands where the finger is and is done.
                    scrollScope.launch { gridState.scrollToItem(index) }
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

/**
 * Android 14 lets the user grant a subset of the library. The grid then genuinely shows fewer
 * items than the device holds, so that gap is stated rather than left for the user to notice —
 * and the fix is a system screen, not something LumoVault can decide for them.
 */
@Composable
private fun LimitedAccessNotice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = GridSpacing, end = RailWidth, top = SpaceSm, bottom = SpaceSm)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.shapes.medium,
            )
            .padding(horizontal = SpaceMd, vertical = SpaceSm),
        horizontalArrangement = Arrangement.spacedBy(SpaceSm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            modifier = Modifier.size(SpaceLg),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.photos_limited_access_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { context.openAppDetailsSettings() },
        ) {
            Text(stringResource(R.string.photos_limited_access_action))
        }
    }
}

@Composable
private fun DayHeader(epochDay: Long, modifier: Modifier = Modifier) {
    val day = LocalDate.ofEpochDay(epochDay)
    val distance = dayDistance(day, LocalDate.now())
    val text = when (distance) {
        DayDistance.Today -> stringResource(R.string.day_today)
        DayDistance.Yesterday -> stringResource(R.string.day_yesterday)
        else -> formatDay(day, distance)
    }

    // The header shares the grid's gutter rather than its own, so the "T" of "Today" lines up with the left
    // edge of the first thumbnail under it: a row of photos and a row of labels that start at different
    // x-positions read as two unrelated lists. The type is smaller and heavier than the `titleMedium` it
    // replaces, which is what makes the pictures the largest thing on the screen.
    Text(
        text = text,
        style = LumoVaultType.sectionHeader,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = GridSpacing, end = RailWidth, top = SpaceLg, bottom = SpaceSm),
    )
}

/**
 * The one state the timeline cannot fix by itself.
 *
 * Drawn through [PlaceholderScreen] like every other dead end in the app, and with the button in its `action`
 * slot rather than a bespoke column, so this screen and the cloud's "not signed in" are the same shape. The
 * wording is generic on purpose: Android 14 offers a partial picker, so the dialog that follows may not be the
 * legacy allow/deny one.
 */
@Composable
private fun PermissionRequired(onRequestAccess: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.photos_permission_title),
        description = stringResource(R.string.photos_permission_body),
        icon = Icons.Filled.PhotoLibrary,
        action = {
            Button(onClick = onRequestAccess) {
                Text(stringResource(R.string.photos_permission_action))
            }
        },
    )
}

/**
 * A scan that did not finish, in the same shape as the empty library: the grid may already hold last run's
 * rows, so this says what went wrong without pretending the answer is known, and offers the one action that
 * can be taken from here.
 */
@Composable
private fun LibraryUnavailable(onRetry: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.photos_error_title),
        description = stringResource(R.string.photos_error_body),
        icon = Icons.Filled.BrokenImage,
        action = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.error_retry))
            }
        },
    )
}

/** Rows of cells fetched ahead of the viewport edge, so scrolling does not hit a blank tail. */
private const val LOAD_AHEAD = 24

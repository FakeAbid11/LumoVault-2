package com.lumovault.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.SubcomposeAsyncImage
import com.lumovault.app.R
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreState
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.ui.components.ActionIcon
import com.lumovault.app.ui.components.CollectAppMessages
import com.lumovault.app.ui.components.LoadingScreen
import com.lumovault.app.ui.components.MediaGlyph
import com.lumovault.app.ui.components.MediaPill
import com.lumovault.app.ui.components.PillTone
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.components.SelectionBar
import com.lumovault.app.ui.components.StatusPill
import com.lumovault.app.ui.components.WorkingScreen
import com.lumovault.app.ui.screens.cloud.RestoreAction
import com.lumovault.app.ui.screens.cloud.CloudUiState
import com.lumovault.app.ui.screens.cloud.CloudViewModel
import com.lumovault.app.ui.screens.cloud.shouldOfferConnectDialog
import com.lumovault.app.util.DayDistance
import com.lumovault.app.util.dayDistance
import com.lumovault.app.util.toByteText
import com.lumovault.app.util.formatDay
import com.lumovault.app.util.formatDuration
import com.lumovault.app.util.MediaShare
import java.time.LocalDate
import com.lumovault.app.ui.theme.FullScreenScrim
import com.lumovault.app.ui.theme.GridCellMinSize
import com.lumovault.app.ui.theme.GridSpacing
import com.lumovault.app.ui.theme.LumoVaultType
import com.lumovault.app.ui.theme.MediaBadgeCorner
import com.lumovault.app.ui.theme.MediaBadgeInset
import com.lumovault.app.ui.theme.MediaBadgeScrim
import com.lumovault.app.ui.theme.MediaThumbCorner
import com.lumovault.app.ui.theme.OnMedia
import com.lumovault.app.ui.theme.OverlayFadeMillis
import com.lumovault.app.ui.theme.SelectionBarInset
import com.lumovault.app.ui.theme.SelectionRing
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXs
import com.lumovault.app.ui.theme.SpaceXxl

/**
 * The cloud library (PRD section 24), drawn from the local cloud index rather than from a message
 * list: Telegram stays invisible, and nothing on this screen can pull down an original.
 *
 * A cell's picture comes from Telegram's *thumbnail* file only. Where that cannot be resolved —
 * because this build carries no TDLib binary, or the transfer has not finished — the cell keeps its
 * labelled placeholder, which is what section 21 asks for instead of a silent full-size fetch.
 *
 * Selection behaves like every other media grid: long press to start, tap to extend, and the shared
 * [SelectionBar] to act. A tap on an item the device holds opens the *shared* viewer on that local row;
 * only an item the device lacks falls back to the details-and-download sheet, because a viewer with no
 * bytes to draw would be a second viewer — and this screen is deliberately not growing one.
 */
@Composable
fun CloudScreen(
    onConnectTelegram: () -> Unit = {},
    onOpenCloudMedia: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: CloudViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val restoreJobs by viewModel.restoreJobs.collectAsStateWithLifecycle()
    val restoreJob by viewModel.restoreJob.collectAsStateWithLifecycle()
    val selectedIds by viewModel.selected.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // One context for the one share utility — this screen never builds an intent itself — and one
    // scope for the single suspend a tap makes (does the pressed item have a local row?).
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Share and selection feedback, shown by the shell's one snackbar: collected before any branch
    // can route around it, so a line is never lost to a state change.
    CollectAppMessages(viewModel.messages)

    var selected by remember { mutableStateOf<CloudMedia?>(null) }
    // `remember`, not `rememberSaveable`: the ask belongs to this visit, and it comes back on the next one
    // while the account is still missing. Persisting it would let a single dismissal silence the tab
    // forever, which is not what a skipped account deserves.
    var connectDialogDismissed by remember { mutableStateOf(false) }

    /**
     * One tap on a cloud item: the shared viewer when the device holds a copy, the details-and-
     * download sheet when it does not. Which of the two is decided by a fresh lookup rather than by
     * the badge the cell drew, because the badge is a flow's last answer and this is the row's state
     * now — opening a pager over a file that has just left the device would be a black screen.
     */
    fun openItem(item: CloudMedia) {
        scope.launch {
            val localId = viewModel.localMediaIdFor(item)
            if (localId != null) {
                onOpenCloudMedia(localId)
            } else {
                selected = item
                viewModel.focusing(item)
            }
        }
    }

    // Re-sync on every resume: session, channel and account can each change while LumoVault is
    // backgrounded, and an answer remembered from last time would be wrong.
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.resume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    PullToRefreshBox(
        isRefreshing = (state as? CloudUiState.Library)?.refreshing == true,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when (val current = state) {
            // The first sync was asked for the moment this screen's ViewModel came up, and has not
            // answered yet. The ring claims only that a question is pending — where silence left a
            // blank frame, and where an empty state would flash "your cloud is empty" over a
            // library that is already indexed.
            CloudUiState.Idle -> LoadingScreen()

            CloudUiState.NotAvailable -> PlaceholderScreen(
                title = stringResource(R.string.cloud_not_available_title),
                description = stringResource(R.string.cloud_not_available_body),
                icon = Icons.Filled.Cloud,
                modifier = Modifier.fillMaxSize(),
            )

            // Sign-in is a state with a door, not a sentence: the button reaches the same credential
            // panels onboarding used, because the onboarding flow itself is gone once setup finished
            // and copy that pointed at it described a screen the app could no longer reach.
            CloudUiState.NeedsSignIn -> PlaceholderScreen(
                title = stringResource(R.string.cloud_needs_signin_title),
                description = stringResource(R.string.cloud_needs_signin_body),
                icon = Icons.Filled.Cloud,
                action = {
                    Button(onClick = onConnectTelegram) {
                        Text(stringResource(R.string.cloud_connect_action))
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            is CloudUiState.Preparing -> WorkingScreen(
                title = stringResource(R.string.cloud_preparing_title),
                detail = stringResource(current.step.labelRes()),
            )

            is CloudUiState.Scanning -> WorkingScreen(
                title = stringResource(R.string.cloud_scanning_title),
                detail = pluralStringResource(R.plurals.cloud_items_found, current.found, current.found),
            )

            CloudUiState.NoMedia -> PlaceholderScreen(
                title = stringResource(R.string.cloud_empty_title),
                description = stringResource(R.string.cloud_empty_body),
                icon = Icons.Filled.Cloud,
                modifier = Modifier.fillMaxSize(),
            )

            is CloudUiState.Failed -> CloudUnavailable(
                failure = current.failure,
                onRetry = viewModel::refresh,
                onConnect = onConnectTelegram,
            )

            is CloudUiState.Library -> CloudTimeline(
                state = current,
                selection = selectedIds,
                onLoadMore = viewModel::loadMore,
                // One tap opens, one long press selects, and after that every tap toggles — the same
                // touch rule as the Photos grid, decided here because it is a fact about the touch
                // and not about the data.
                onCellClick = { item ->
                    if (selectedIds.isEmpty()) openItem(item) else viewModel.toggleSelection(item.messageId)
                },
                onCellLongClick = { item -> viewModel.toggleSelection(item.messageId) },
                previewPathFor = viewModel::previewPath,
                restoreJobs = restoreJobs,
            )
        }

        // The strip every media selection acts from — the same [SelectionBar] Photos and the album
        // screens draw, with the same count rule: this size is the whole collection's selection, not
        // the loaded page, because select-all read ids the grid never loaded.
        if (selectedIds.isNotEmpty()) {
            SelectionBar(
                label = pluralStringResource(R.plurals.selected_count, selectedIds.size, selectedIds.size),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(GridSpacing),
            ) {
                // Select all leads because it speaks for the selection itself, then share, then the
                // download — the one action that costs the user's data, under the thumb — then clear.
                // The same order and the same glyphs the other strips teach.
                ActionIcon(Icons.Filled.SelectAll, R.string.selection_select_all, viewModel::selectAll)
                ActionIcon(Icons.Filled.Share, R.string.share_action) {
                    viewModel.shareSelected { items -> MediaShare.share(context, items) }
                }
                ActionIcon(Icons.Filled.CloudDownload, R.string.cloud_download_action, viewModel::restoreSelected)
                ActionIcon(Icons.Filled.Close, R.string.selection_clear, viewModel::clearSelection)
            }
        }
    }

    // Asked once per visit, because "skip for now" was a choice and this tab has no business arguing with
    // it every frame. Dismissing costs nothing: the placeholder behind the dialog keeps the same button for
    // as long as the user stays on the screen.
    if (shouldOfferConnectDialog(state, connectDialogDismissed)) {
        AlertDialog(
            onDismissRequest = { connectDialogDismissed = true },
            title = { Text(stringResource(R.string.cloud_connect_dialog_title)) },
            text = { Text(stringResource(R.string.cloud_connect_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        connectDialogDismissed = true
                        onConnectTelegram()
                    },
                ) {
                    Text(stringResource(R.string.cloud_connect_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { connectDialogDismissed = true }) {
                    Text(stringResource(R.string.cloud_connect_dialog_dismiss))
                }
            },
        )
    }

    // Back means "stop the top-most thing this screen is doing": the open sheet, then the
    // selection — and never the tab, which a back press from a selection strip would otherwise take
    // with it. The Photos grid orders it the same way.
    BackHandler(enabled = selected != null || selectedIds.isNotEmpty()) {
        if (selected != null) {
            selected = null
            viewModel.focusing(null)
        } else {
            viewModel.clearSelection()
        }
    }

    // The overlay exits over the picture it was showing, not over the grid behind it: `selected` is null the
    // moment dismissal begins, so a remembered copy of the last selection keeps that frame on screen for the
    // length of the fade. The copy is written during composition rather than in an effect, because an effect
    // runs after the exit's first frame — the overlay would show the grid for one frame before it caught up.
    var overlayItem by remember { mutableStateOf(selected) }
    if (selected != null) overlayItem = selected

    AnimatedVisibility(
        visible = selected != null,
        enter = fadeIn(tween(OverlayFadeMillis)),
        exit = fadeOut(tween(OverlayFadeMillis)),
    ) {
        overlayItem?.let { item ->
            CloudViewer(
                item = item,
                onDevice = (state as? CloudUiState.Library)?.localMatches?.contains(item.messageId) == true,
                previewPathFor = viewModel::previewPath,
                job = restoreJob?.takeIf { it.messageId == item.messageId },
                onDownload = { viewModel.restore(item) },
                onCancel = { viewModel.cancelRestore(item) },
                onDismiss = {
                    selected = null
                    viewModel.focusing(null)
                },
            )
        }
    }
}

@Composable
private fun CloudTimeline(
    state: CloudUiState.Library,
    selection: Set<Long>,
    onLoadMore: () -> Unit,
    onCellClick: (CloudMedia) -> Unit,
    onCellLongClick: (CloudMedia) -> Unit,
    previewPathFor: suspend (CloudMedia) -> String?,
    restoreJobs: Map<Long, RestoreJob>,
) {
    val gridState = rememberLazyGridState()
    val renderedRows = state.days.sumOf { it.items.size } + state.days.size + 1

    LaunchedEffect(gridState, renderedRows, state.hasMoreToLoad) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (state.hasMoreToLoad && lastVisible >= renderedRows - LOAD_AHEAD) onLoadMore()
            }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = GridCellMinSize),
        state = gridState,
        // The strip's room at the foot, the same number as the Photos grid's: without it the last
        // row sits under the selection bar — the cell the user was reaching for.
        contentPadding = PaddingValues(
            start = GridSpacing,
            top = GridSpacing,
            end = GridSpacing,
            bottom = if (selection.isNotEmpty()) SelectionBarInset else GridSpacing,
        ),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "cloud-header", span = { GridItemSpan(maxLineSpan) }) {
            CloudHeader(
                state = state,
                modifier = Modifier.animateItem(),
            )
        }

        state.days.forEach { day ->
            item(key = "cloud-day-${day.epochDay}", span = { GridItemSpan(maxLineSpan) }) {
                CloudDayHeader(
                    epochDay = day.epochDay,
                    modifier = Modifier.animateItem(),
                )
            }
            items(items = day.items, key = { item -> "cloud-${item.messageId}" }) { item ->
                val job = restoreJobs[item.messageId]
                CloudMediaCell(
                    item = item,
                    modifier = Modifier.animateItem(),
                    onDevice = item.messageId in state.localMatches,
                    selected = item.messageId in selection,
                    onClick = { onCellClick(item) },
                    onLongClick = { onCellLongClick(item) },
                    previewPathFor = previewPathFor,
                    restoring = job?.state?.isLive == true,
                    failed = job?.state == RestoreState.Failed,
                )
            }
        }
    }
}

/** Header line, built only from counts the index actually holds. */
@Composable
private fun CloudHeader(state: CloudUiState.Library, modifier: Modifier = Modifier) {
    // Plurals are resolved through Resources rather than pluralStringResource inside the lambda: a
    // @Composable call is only allowed in composable scope, and joinToString's transform is an ordinary
    // function type. The wording and the resource names are unchanged.
    val resources = LocalContext.current.resources
    val countsText = state.counts
        .joinToString(separator = " · ") { (type, count) -> resources.getQuantityString(type.cloudCountRes(), count, count) }
        .ifBlank { resources.getQuantityString(R.plurals.cloud_items_found, state.totalCount, state.totalCount) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = GridSpacing, vertical = SpaceSm),
        verticalArrangement = Arrangement.spacedBy(SpaceXs),
    ) {
        Text(
            text = countsText,
            // The count is the header of a list, not a title: at `titleMedium` it out-shouts every day header
            // under it, and the one number a user reads here is which of their photos are where.
            style = LumoVaultType.sectionHeader,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // The two notices are mutually exclusive by construction: a stale library is not also being
        // refreshed, and saying both would be one claim too many. They wear chips now, like every other
        // screen's answer to "what state am I in" — stale takes the outline rather than the error fill,
        // because the library on screen is still correct, just not current; checking takes the quiet fill,
        // because it is expected and over in a moment.
        //
        // The stale chip says "offline" only when the app was told Telegram could not be reached. The other
        // ways to end up here — a build with no Telegram, a rejected channel marker — are not network
        // conditions, and one sentence covering all three named the wrong cause for two of them.
        when {
            state.fromCache -> StatusPill(
                text = stringResource(
                    if (state.offline) R.string.cloud_offline_notice else R.string.cloud_cached_notice,
                ),
                tone = PillTone.Missing,
            )

            state.refreshing -> StatusPill(
                text = stringResource(R.string.cloud_refreshing_notice),
                tone = PillTone.Neutral,
            )
        }
    }
}

@Composable
private fun CloudDayHeader(epochDay: Long, modifier: Modifier = Modifier) {
    val day = LocalDate.ofEpochDay(epochDay)
    val distance = dayDistance(day, LocalDate.now())
    val text = when (distance) {
        DayDistance.Today -> stringResource(R.string.day_today)
        DayDistance.Yesterday -> stringResource(R.string.day_yesterday)
        else -> formatDay(day, distance)
    }

    // The same header the local timeline draws, because the two screens are the same library seen from two
    // ends of a cable, and a day that is 15 sp semi-bold on one and 16 sp regular on the other reads as two
    // different apps.
    Text(
        text = text,
        style = LumoVaultType.sectionHeader,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = GridSpacing, end = GridSpacing, top = SpaceLg, bottom = SpaceSm),
    )
}

/**
 * One remote item.
 *
 * The corners mean the same thing they mean on a local thumbnail, which is the whole reason this cell draws
 * through the same two components: a duration bottom-end, a "GIF" tag bottom-end, a play mark bottom-start. A
 * user who has learned where the timeline puts a mark should not have to relearn it in the cloud.
 *
 * Top-start is the cell's journey, one mark at a time, where the local grid puts a photo's backup
 * state — the same corner for the same kind of question, "what has happened to this file". Cloud-only
 * is *stated* rather than left implied: unlike the local grid's default, it mixes with "on this
 * device" on one screen, and the difference is the question this screen exists to answer. A failed
 * download wears the retry mark above everything but a selection's tick, which replaces them all while
 * it is chosen — one mark per cell, never a stack.
 */
@Composable
private fun CloudMediaCell(
    item: CloudMedia,
    modifier: Modifier = Modifier,
    onDevice: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    previewPathFor: suspend (CloudMedia) -> String?,
    restoring: Boolean,
    failed: Boolean,
) {
    var previewPath by remember(item.messageId, item.previewRemoteFileId) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.messageId, item.previewRemoteFileId) {
        previewPath = awaitPreview(item, previewPathFor)
    }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(MediaThumbCorner))
            // Long press starts a selection and every later tap extends it — the touch rule the
            // Photos grid teaches, passed down here as two plain callbacks.
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .border(
                width = if (selected) SelectionRing else 0.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                shape = RoundedCornerShape(MediaThumbCorner),
            ),
    ) {
        val path = previewPath
        if (path.isNullOrBlank()) {
            CloudCellPlaceholder(broken = false)
        } else {
            SubcomposeAsyncImage(
                // A path TDLib produced for the *thumbnail*. Nothing in this chain can request the
                // original, which is what keeps grid rendering inside PRD section 25.
                model = Uri.parse("file://$path"),
                contentDescription = stringResource(item.type.kindRes()),
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
                loading = { CloudCellPlaceholder(broken = false) },
                error = { CloudCellPlaceholder(broken = true) },
            )
        }

        // One mark, decided in one place. The tick leads — a cell being chosen has no interesting
        // state to show at the same moment — then a failed download (the state with a remedy), then
        // the honest pair: on this device, or in the cloud.
        when {
            selected -> MediaGlyph(
                icon = Icons.Filled.CheckCircle,
                contentDescription = stringResource(R.string.backup_cell_selected),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
            )

            failed -> MediaGlyph(
                icon = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.cloud_badge_download_failed),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
            )

            onDevice -> MediaGlyph(
                icon = Icons.Filled.CloudDone,
                contentDescription = stringResource(R.string.cloud_badge_on_device),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
            )

            else -> MediaGlyph(
                icon = Icons.Filled.Cloud,
                contentDescription = stringResource(R.string.cloud_badge_cloud_only),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(MediaBadgeInset),
            )
        }

        if (item.type == MediaType.Gif) {
            MediaPill(
                text = stringResource(R.string.media_badge_gif),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(MediaBadgeInset),
            )
        } else if (item.type == MediaType.Video) {
            MediaGlyph(
                icon = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(MediaBadgeInset),
            )
            item.durationSeconds?.let { seconds ->
                MediaPill(
                    text = formatDuration(seconds * MILLIS_PER_SECOND),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(MediaBadgeInset),
                    textAlign = TextAlign.End,
                )
            }
        }

        // A restore in flight is drawn on the cell as well as in the sheet, because the sheet closes and
        // the download does not: the user needs to see which of these pictures is still arriving.
        if (restoring) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = OnMedia,
                trackColor = MediaBadgeScrim,
            )
        }
    }
}

@Composable
private fun CloudCellPlaceholder(broken: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (broken) Icons.Filled.BrokenImage else Icons.Filled.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(PlaceholderIconSize),
            tint = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * Tapping a remote item (PRD section 26): metadata and the remote preview, nothing else.
 *
 * Opening this never fetches the original, and the one control that would is disabled with its
 * reason stated — the download engine belongs to the restore phase, so an enabled button here would
 * either do nothing or lie about having done something.
 */
@Composable
private fun CloudViewer(
    item: CloudMedia,
    onDevice: Boolean,
    previewPathFor: suspend (CloudMedia) -> String?,
    job: RestoreJob?,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    var previewPath by remember(item.messageId) { mutableStateOf<String?>(null) }
    // "still asking" and "asked and got nothing" are different facts. Naming a preview that has not
    // arrived yet "broken" tells the user their thumbnail is damaged while the call that would prove
    // it has not even returned.
    var previewAttempted by remember(item.messageId) { mutableStateOf(false) }

    // Resolved here because the semantics lambda below is not a composable context.
    val closeLabel = stringResource(R.string.viewer_close)

    LaunchedEffect(item) {
        previewPath = awaitPreview(item, previewPathFor)
        previewAttempted = true
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        // The scrim carries its own node, with its own name. On the root it was the opposite: one click
        // handler merged the whole dialog — its text, its buttons, its disabled download — into a single
        // unnamed row, so a screen reader offered the entire sheet as one thing you could not identify
        // and never mentioned the way out of it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(FullScreenScrim)
                .semantics { contentDescription = closeLabel }
                .clickable(role = Role.Button, onClick = onDismiss),
        )
        Surface(
            modifier = Modifier.fillMaxWidth().padding(SpaceXxl),
            shape = RoundedCornerShape(MediaThumbCorner),
            tonalElevation = DialogTonalElevation,
        ) {
            Column(
                modifier = Modifier
                    .padding(SpaceLg)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpaceMd),
            ) {
                val path = previewPath
                if (path.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(ViewerPreviewHeight)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(MediaBadgeCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloudCellPlaceholder(broken = previewAttempted && item.hasPreview)
                    }
                } else {
                    SubcomposeAsyncImage(
                        model = Uri.parse("file://$path"),
                        contentDescription = stringResource(item.type.kindRes()),
                        modifier = Modifier.fillMaxWidth().height(ViewerPreviewHeight)
                            .clip(RoundedCornerShape(MediaBadgeCorner)),
                        contentScale = ContentScale.Fit,
                        loading = { CloudCellPlaceholder(broken = false) },
                        error = { CloudCellPlaceholder(broken = true) },
                    )
                }

                Text(
                    text = item.fileName.ifBlank { stringResource(R.string.cloud_viewer_unnamed) },
                    style = LumoVaultType.itemTitle,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                )

                Text(
                    text = buildString {
                        append(stringResource(item.type.kindRes()))
                        if (item.durationSeconds != null) {
                            append(" · ").append(formatDuration(item.durationSeconds * MILLIS_PER_SECOND))
                        }
                        if (item.hasDimensions) {
                            append(" · ").append(item.width).append("×").append(item.height)
                        }
                        append(" · ").append(stringResource(R.string.cloud_viewer_size, item.sizeBytes.toByteText()))
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // One line, not two: where the file is and how sure we are of its date are the same kind of
                // footnote, and stacked they pushed the one control on this sheet below the fold on a short
                // screen.
                Text(
                    text = stringResource(
                        if (onDevice) R.string.cloud_badge_on_device else R.string.cloud_badge_cloud_only,
                    ) + " · " + stringResource(R.string.cloud_viewer_dates_from_message) + " · " + stringResource(
                        if (item.hasPreview) R.string.cloud_viewer_preview_shown
                        else R.string.cloud_viewer_no_preview,
                    ),
                    style = LumoVaultType.sectionDetail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                RestoreAction(
                    job = job,
                    onDownload = onDownload,
                    onCancel = onCancel,
                )

                Text(
                    text = stringResource(R.string.restore_cloud_remains),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.cloud_viewer_close))
                }
            }
        }
    }
}

@Composable
private fun CloudUnavailable(
    failure: CloudFailure,
    onRetry: () -> Unit,
    onConnect: () -> Unit,
) {
    // What happened, then what the user can do about it — one door per cause. The cause decides
    // both the words and the button: a signed-out session's remedy is a door, not a retry, and a
    // rate limit's remedy is patience rather than a red error. Nothing here ever shows the
    // exception or the status code behind it; those stay in the log, class name only.
    val kind = failure.kind
    val title = when (kind) {
        CloudFailure.Kind.NotAuthenticated -> R.string.cloud_needs_signin_title
        CloudFailure.Kind.RateLimited -> R.string.cloud_error_paused_title

        CloudFailure.Kind.ChannelUnusable,
        CloudFailure.Kind.ChannelCreationFailed,
        CloudFailure.Kind.MarkerRejected,
        -> R.string.cloud_error_setup_title

        else -> R.string.cloud_error_title
    }
    val body = when (kind) {
        CloudFailure.Kind.NotAuthenticated -> R.string.cloud_needs_signin_body
        CloudFailure.Kind.RateLimited -> R.string.cloud_error_paused_body

        CloudFailure.Kind.ChannelUnusable,
        CloudFailure.Kind.ChannelCreationFailed,
        CloudFailure.Kind.MarkerRejected,
        -> R.string.cloud_error_setup_body

        else -> R.string.cloud_error_body
    }

    PlaceholderScreen(
        title = stringResource(title),
        description = stringResource(body),
        icon = if (kind == CloudFailure.Kind.NotAuthenticated) Icons.Filled.Cloud else Icons.Filled.CloudOff,
        action = {
            if (kind == CloudFailure.Kind.NotAuthenticated) {
                Button(onClick = onConnect) {
                    Text(stringResource(R.string.cloud_connect_action))
                }
            } else {
                Button(onClick = onRetry) {
                    Text(stringResource(R.string.error_retry))
                }
            }
        },
    )
}

private fun MediaType.kindRes(): Int = when (this) {
    MediaType.Photo -> R.string.media_kind_photo
    MediaType.Video -> R.string.media_kind_video
    MediaType.Gif -> R.string.media_kind_gif
}

private fun MediaType.cloudCountRes(): Int = when (this) {
    MediaType.Photo -> R.plurals.cloud_count_photos
    MediaType.Video -> R.plurals.cloud_count_videos
    MediaType.Gif -> R.plurals.cloud_count_gifs
}

private fun CloudUiState.Preparing.Step.labelRes(): Int = when (this) {
    CloudUiState.Preparing.Step.Searching -> R.string.cloud_step_searching
    CloudUiState.Preparing.Step.Validating -> R.string.cloud_step_validating
    CloudUiState.Preparing.Step.Creating -> R.string.cloud_step_creating
}

private val ViewerPreviewHeight = 260.dp

/** Material 3's own elevation for a dialog: tone, not shadow, because the scrim already separates it. */
private val DialogTonalElevation = 3.dp

/** Bigger than a corner mark and smaller than the cell: a placeholder is the whole cell's content. */
private val PlaceholderIconSize = 22.dp

private const val MILLIS_PER_SECOND = 1000L

/** Cells fetched ahead of the viewport edge, so scrolling does not hit a blank tail. */
private const val LOAD_AHEAD = 24

/**
 * A bounded re-ask for a thumbnail that has not arrived.
 *
 * The first request can fail for reasons that cure themselves a moment later: TDLib is still completing
 * its handshake when the grid first draws, or `downloadFile` has started and the bytes are on their way.
 * Asking once and remembering the answer would then leave a placeholder sitting over a picture that
 * exists, with nothing to bring it back but scrolling the row out and in again — which is how a cloud
 * gallery reads as broken when nothing about the library is.
 *
 * The attempts are few and the wait short, because a cell that genuinely has no thumbnail should reach its
 * honest placeholder rather than poll forever, and every visible cell is doing this at once.
 */
private suspend fun awaitPreview(
    item: CloudMedia,
    previewPathFor: suspend (CloudMedia) -> String?,
): String? {
    // An item with no stored thumbnail reference is not a retry candidate: nothing was ever there to
    // fetch, and four asks would only delay the placeholder that says so.
    if (!item.hasPreview) return null

    var attempt = 0
    while (true) {
        val path = previewPathFor(item)
        if (path != null || ++attempt >= PREVIEW_ATTEMPTS) return path
        delay(PREVIEW_RETRY_MILLIS)
    }
}

/** How many times a cell asks for a preview before it shows the placeholder. */
private const val PREVIEW_ATTEMPTS = 4

/** The pause between those asks. */
private const val PREVIEW_RETRY_MILLIS = 400L

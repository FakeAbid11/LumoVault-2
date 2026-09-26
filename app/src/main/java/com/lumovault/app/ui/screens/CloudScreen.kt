package com.lumovault.app.ui.screens

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import com.lumovault.app.ui.components.MediaGlyph
import com.lumovault.app.ui.components.MediaPill
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.screens.cloud.RestoreAction
import com.lumovault.app.ui.screens.cloud.CloudUiState
import com.lumovault.app.ui.screens.cloud.CloudViewModel
import com.lumovault.app.util.DayDistance
import com.lumovault.app.util.dayDistance
import com.lumovault.app.util.toByteText
import com.lumovault.app.util.formatDay
import com.lumovault.app.util.formatDuration
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
import com.lumovault.app.ui.theme.SpaceLg
import com.lumovault.app.ui.theme.SpaceMd
import com.lumovault.app.ui.theme.SpaceSm
import com.lumovault.app.ui.theme.SpaceXl
import com.lumovault.app.ui.theme.SpaceXs

/**
 * The cloud library (PRD section 24), drawn from the local cloud index rather than from a message
 * list: Telegram stays invisible, and nothing on this screen can pull down an original.
 *
 * A cell's picture comes from Telegram's *thumbnail* file only. Where that cannot be resolved —
 * because this build carries no TDLib binary, or the transfer has not finished — the cell keeps its
 * labelled placeholder, which is what section 21 asks for instead of a silent full-size fetch.
 */
@Composable
fun CloudScreen(
    modifier: Modifier = Modifier,
    viewModel: CloudViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val restoreJobs by viewModel.restoreJobs.collectAsStateWithLifecycle()
    val restoreJob by viewModel.restoreJob.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    var selected by remember { mutableStateOf<CloudMedia?>(null) }

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
            // Nothing asked for yet; drawing neither a spinner nor an empty state avoids a flash of
            // "your cloud is empty" over a library that is already indexed.
            CloudUiState.Idle -> Unit

            CloudUiState.NotAvailable -> PlaceholderScreen(
                title = stringResource(R.string.cloud_not_available_title),
                description = stringResource(R.string.cloud_not_available_body),
                icon = Icons.Filled.Cloud,
                modifier = Modifier.fillMaxSize(),
            )

            CloudUiState.NeedsSignIn -> PlaceholderScreen(
                title = stringResource(R.string.cloud_needs_signin_title),
                description = stringResource(R.string.cloud_needs_signin_body),
                icon = Icons.Filled.Cloud,
                modifier = Modifier.fillMaxSize(),
            )

            is CloudUiState.Preparing -> Working(
                title = stringResource(R.string.cloud_preparing_title),
                detail = stringResource(current.step.labelRes()),
            )

            is CloudUiState.Scanning -> Working(
                title = stringResource(R.string.cloud_scanning_title),
                detail = pluralStringResource(R.plurals.cloud_items_found, current.found, current.found),
            )

            CloudUiState.NoMedia -> PlaceholderScreen(
                title = stringResource(R.string.cloud_empty_title),
                description = stringResource(R.string.cloud_empty_body),
                icon = Icons.Filled.Cloud,
                modifier = Modifier.fillMaxSize(),
            )

            is CloudUiState.Failed -> CloudUnavailable(onRetry = viewModel::refresh)

            is CloudUiState.Library -> CloudTimeline(
                state = current,
                onLoadMore = viewModel::loadMore,
                onSelect = { item ->
                    selected = item
                    viewModel.focusing(item)
                },
                previewPathFor = viewModel::previewPath,
                restoreJobs = restoreJobs,
            )
        }
    }

    // The sheet already dismisses on a tap anywhere outside the picture, but the system back gesture was
    // not one of them — and a full-screen overlay that swallows back feels stuck rather than focused.
    BackHandler(enabled = selected != null) { selected = null }

    selected?.let { item ->
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

@Composable
private fun CloudTimeline(
    state: CloudUiState.Library,
    onLoadMore: () -> Unit,
    onSelect: (CloudMedia) -> Unit,
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
        contentPadding = PaddingValues(GridSpacing),
        horizontalArrangement = Arrangement.spacedBy(GridSpacing),
        verticalArrangement = Arrangement.spacedBy(GridSpacing),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "cloud-header", span = { GridItemSpan(maxLineSpan) }) {
            CloudHeader(state = state)
        }

        state.days.forEach { day ->
            item(key = "cloud-day-${day.epochDay}", span = { GridItemSpan(maxLineSpan) }) {
                CloudDayHeader(epochDay = day.epochDay)
            }
            items(items = day.items, key = { item -> "cloud-${item.messageId}" }) { item ->
                CloudMediaCell(
                    item = item,
                    onDevice = item.messageId in state.localMatches,
                    onClick = { onSelect(item) },
                    previewPathFor = previewPathFor,
                    restoring = restoreJobs[item.messageId]?.state?.isLive == true,
                )
            }
        }
    }
}

/** Header line, built only from counts the index actually holds. */
@Composable
private fun CloudHeader(state: CloudUiState.Library) {
    // Plurals are resolved through Resources rather than pluralStringResource inside the lambda: a
    // @Composable call is only allowed in composable scope, and joinToString's transform is an ordinary
    // function type. The wording and the resource names are unchanged.
    val resources = LocalContext.current.resources
    val countsText = state.counts
        .joinToString(separator = " · ") { (type, count) -> resources.getQuantityString(type.cloudCountRes(), count, count) }
        .ifBlank { resources.getQuantityString(R.plurals.cloud_items_found, state.totalCount, state.totalCount) }

    Column(
        modifier = Modifier
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
        // refreshed, and saying both would be one claim too many.
        when {
            state.fromCache -> Text(
                text = stringResource(R.string.cloud_offline_notice),
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.refreshing -> Text(
                text = stringResource(R.string.cloud_refreshing_notice),
                style = LumoVaultType.sectionDetail,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloudDayHeader(epochDay: Long) {
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
        modifier = Modifier
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
 * "On this device" is the only state with a mark, and it is drawn at top-start where the local grid puts a
 * photo's backup state — the same corner for the same kind of question, "what has happened to this file".
 * "Cloud only" draws nothing, for the reason the local cell gives for an un-backuped photo: it is where every
 * item on this screen starts, so marking it is not information, it is the grid covered in clouds until the few
 * cells that differ stop standing out.
 */
@Composable
private fun CloudMediaCell(
    item: CloudMedia,
    onDevice: Boolean,
    onClick: () -> Unit,
    previewPathFor: suspend (CloudMedia) -> String?,
    restoring: Boolean,
) {
    var previewPath by remember(item.messageId, item.previewRemoteFileId) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.messageId, item.previewRemoteFileId) {
        previewPath = previewPathFor(item)
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(MediaThumbCorner))
            .clickable(onClick = onClick),
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

        if (onDevice) {
            MediaGlyph(
                icon = Icons.Filled.CloudDone,
                contentDescription = stringResource(R.string.cloud_badge_on_device),
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

    LaunchedEffect(item) { previewPath = previewPathFor(item) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FullScreenScrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(MediaThumbCorner),
            tonalElevation = 3.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val path = previewPath
                if (path.isNullOrBlank()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(ViewerPreviewHeight)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(MediaBadgeCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloudCellPlaceholder(broken = item.hasPreview)
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
private fun Working(title: String, detail: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = SpaceXl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceMd, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
        Text(text = title, style = LumoVaultType.sectionHeader)
        // The count underneath is the reassuring part: a sync that shows a number going up is working, and a
        // spinner alone cannot tell a first run from a stall.
        Text(
            text = detail,
            style = LumoVaultType.sectionDetail,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CloudUnavailable(onRetry: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.cloud_error_title),
        description = stringResource(R.string.cloud_error_body),
        icon = Icons.Filled.CloudOff,
        action = {
            Button(onClick = onRetry) {
                Text(stringResource(R.string.error_retry))
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

/** Bigger than a corner mark and smaller than the cell: a placeholder is the whole cell's content. */
private val PlaceholderIconSize = 22.dp

private const val MILLIS_PER_SECOND = 1000L

/** Cells fetched ahead of the viewport edge, so scrolling does not hit a blank tail. */
private const val LOAD_AHEAD = 24

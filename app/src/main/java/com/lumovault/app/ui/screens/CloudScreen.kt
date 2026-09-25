package com.lumovault.app.ui.screens

import android.net.Uri
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
import androidx.compose.ui.graphics.Color
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
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.screens.cloud.RestoreAction
import com.lumovault.app.ui.screens.cloud.CloudUiState
import com.lumovault.app.ui.screens.cloud.CloudViewModel
import com.lumovault.app.util.DayDistance
import com.lumovault.app.util.dayDistance
import com.lumovault.app.util.formatDay
import com.lumovault.app.util.formatDuration
import java.time.LocalDate

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
        columns = GridCells.Adaptive(minSize = CellMinSize),
        state = gridState,
        contentPadding = PaddingValues(CellSpacing),
        horizontalArrangement = Arrangement.spacedBy(CellSpacing),
        verticalArrangement = Arrangement.spacedBy(CellSpacing),
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = CellSpacing, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = countsText,
            style = MaterialTheme.typography.titleMedium,
        )

        // The two notices are mutually exclusive by construction: a stale library is not also being
        // refreshed, and saying both would be one claim too many.
        when {
            state.fromCache -> Text(
                text = stringResource(R.string.cloud_offline_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.refreshing -> Text(
                text = stringResource(R.string.cloud_refreshing_notice),
                style = MaterialTheme.typography.bodySmall,
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

    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = CellSpacing, vertical = 10.dp),
    )
}

/**
 * One remote item. The corner marks are deliberately quiet: this is a photo library, not a file
 * manager, so "cloud only" versus "also on this device" is a badge rather than a column of status.
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
            .clip(RoundedCornerShape(CellCorner))
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

        // A restore in flight is drawn on the cell as well as in the sheet, because the sheet closes and
        // the download does not: the user needs to see which of these pictures is still arriving.
        if (restoring) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                color = OnMediaScrim,
            )
        }

        if (item.type == MediaType.Video) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.align(Alignment.BottomStart).padding(BadgeInset).size(BadgeIconSize),
                tint = OnMediaScrim,
            )
        }

        item.durationSeconds?.let { seconds ->
            Badge(
                text = formatDuration(seconds * MILLIS_PER_SECOND),
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }

        if (item.type == MediaType.Gif) {
            Badge(
                text = stringResource(R.string.media_badge_gif),
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }

        Badge(
            text = stringResource(if (onDevice) R.string.cloud_badge_on_device else R.string.cloud_badge_cloud_only),
            modifier = Modifier.align(Alignment.TopStart),
        )
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(BadgeInset),
        shape = RoundedCornerShape(SmallCorner),
        color = BadgeScrim,
        contentColor = OnMediaScrim,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = BadgePadding, vertical = 1.dp),
        )
    }
}

@Composable
private fun CloudCellPlaceholder(broken: Boolean) {
    Box(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (broken) Icons.Filled.BrokenImage else Icons.Filled.Cloud,
            contentDescription = null,
            modifier = Modifier.size(BadgeIconSize),
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
            .background(ViewerScrim)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            shape = RoundedCornerShape(CellCorner),
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
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(SmallCorner)),
                        contentAlignment = Alignment.Center,
                    ) {
                        CloudCellPlaceholder(broken = item.hasPreview)
                    }
                } else {
                    SubcomposeAsyncImage(
                        model = Uri.parse("file://$path"),
                        contentDescription = stringResource(item.type.kindRes()),
                        modifier = Modifier.fillMaxWidth().height(ViewerPreviewHeight)
                            .clip(RoundedCornerShape(SmallCorner)),
                        contentScale = ContentScale.Fit,
                        loading = { CloudCellPlaceholder(broken = false) },
                        error = { CloudCellPlaceholder(broken = true) },
                    )
                }

                Text(
                    text = item.fileName.ifBlank { stringResource(R.string.cloud_viewer_unnamed) },
                    style = MaterialTheme.typography.titleMedium,
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

                Text(
                    text = stringResource(
                        if (onDevice) R.string.cloud_badge_on_device else R.string.cloud_badge_cloud_only,
                    ) + " · " + stringResource(R.string.cloud_viewer_dates_from_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = if (item.hasPreview) {
                        stringResource(R.string.cloud_viewer_preview_shown)
                    } else {
                        stringResource(R.string.cloud_viewer_no_preview)
                    },
                    style = MaterialTheme.typography.bodySmall,
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CloudUnavailable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.cloud_error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.cloud_error_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.error_retry))
        }
    }
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

/** Bytes to a human line, without pretending a zero is an unknown. */
private fun Long.toByteText(): String = when {
    this <= 0L -> "0 B"
    this >= 1_000_000L -> "%.1f MB".format(this / 1_000_000.0)
    this >= 1_000L -> "%.0f kB".format(this / 1_000.0)
    else -> "$this B"
}

private val CellMinSize = 110.dp
private val CellSpacing = 2.dp
private val CellCorner = 4.dp
private val SmallCorner = 3.dp
private val BadgeInset = 6.dp
private val BadgeIconSize = 18.dp
private val BadgePadding = 4.dp
private val ViewerPreviewHeight = 260.dp
private val BadgeScrim = Color(0xB3000000)
private val OnMediaScrim = Color(0xFFFFFFFF)
private val ViewerScrim = Color(0xCC000000)

private const val MILLIS_PER_SECOND = 1000L

/** Cells fetched ahead of the viewport edge, so scrolling does not hit a blank tail. */
private const val LOAD_AHEAD = 24

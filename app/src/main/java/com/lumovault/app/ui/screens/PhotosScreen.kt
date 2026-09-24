package com.lumovault.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumovault.app.R
import com.lumovault.app.ui.components.MediaCell
import com.lumovault.app.ui.components.PlaceholderScreen
import com.lumovault.app.ui.screens.photos.PhotosUiState
import com.lumovault.app.ui.screens.photos.PhotosViewModel
import com.lumovault.app.util.DayDistance
import com.lumovault.app.util.dayDistance
import com.lumovault.app.util.formatDay
import java.time.LocalDate

/**
 * The local library. It reads one value — [PhotosUiState] — and draws that, so a combination the
 * screen cannot actually be in has nowhere to come from.
 *
 * No MediaStore query, database access or bitmap handling exists below this point.
 */
@Composable
fun PhotosScreen(
    modifier: Modifier = Modifier,
    viewModel: PhotosViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

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

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        when (val current = state) {
            // The first permission read has not arrived yet; drawing nothing avoids a flash of
            // "no photos" on a device that has thousands.
            PhotosUiState.CheckingAccess -> Unit

            PhotosUiState.PermissionRequired -> PermissionRequired(
                onRequestAccess = {
                    permissionLauncher.launch(viewModel.mediaPermissionsToRequest().toTypedArray())
                },
            )

            PhotosUiState.Scanning -> Scanning(found = scanProgress)

            PhotosUiState.Empty -> PlaceholderScreen(
                title = stringResource(R.string.photos_empty_title),
                description = stringResource(R.string.photos_empty_body),
                icon = Icons.Filled.PhotoLibrary,
                modifier = Modifier.fillMaxSize(),
            )

            is PhotosUiState.Failure -> LibraryUnavailable(onRetry = viewModel::refresh)

            is PhotosUiState.Content -> Timeline(
                state = current,
                onLoadMore = viewModel::loadMore,
            )
        }
    }
}

@Composable
private fun Timeline(state: PhotosUiState.Content, onLoadMore: () -> Unit) {
    val gridState = rememberLazyGridState()
    val renderedRows = state.days.sumOf { it.items.size } + state.days.size +
        if (state.limitedAccess) 1 else 0

    LaunchedEffect(gridState, renderedRows, state.hasMoreToLoad) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { lastVisible ->
                if (state.hasMoreToLoad && lastVisible >= renderedRows - LOAD_AHEAD) {
                    onLoadMore()
                }
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
        if (state.limitedAccess) {
            item(key = "limited-access", span = { GridItemSpan(maxLineSpan) }) {
                LimitedAccessNotice()
            }
        }

        state.days.forEach { day ->
            item(key = "day-${day.epochDay}", span = { GridItemSpan(maxLineSpan) }) {
                DayHeader(epochDay = day.epochDay)
            }
            items(items = day.items, key = { media -> media.id }) { media ->
                MediaCell(media = media)
            }
        }
    }
}

/**
 * Android 14 lets the user grant a subset of the library. The grid then genuinely shows fewer
 * items than the device holds, so that gap is stated rather than left for the user to notice —
 * and the fix is a system screen, not something LumoVault can decide for them.
 */
@Composable
private fun LimitedAccessNotice() {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CellSpacing, vertical = 6.dp)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(CornerShape),
            )
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.photos_limited_access_notice),
            style = MaterialTheme.typography.bodyMedium,
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

private fun Context.openAppDetailsSettings() = runCatching {
    startActivity(
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ),
    )
}

@Composable
private fun DayHeader(epochDay: Long) {
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
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CellSpacing, vertical = 10.dp),
    )
}

@Composable
private fun PermissionRequired(onRequestAccess: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoLibrary,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(R.string.photos_permission_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            // Wording kept generic: Android 14 offers a partial picker, so the dialog itself may
            // differ from the legacy allow/deny one.
            text = stringResource(R.string.photos_permission_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRequestAccess) {
            Text(stringResource(R.string.photos_permission_action))
        }
    }
}

/**
 * Shows how many rows have actually been indexed rather than a percentage: the scan cannot know a
 * total before it has finished, and inventing one would be a number the user watches stall.
 */
@Composable
private fun Scanning(found: Int) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Text(
                text = stringResource(R.string.photos_scanning_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(R.string.photos_scanning_progress, found),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LibraryUnavailable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.photos_error_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.photos_error_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(R.string.error_retry))
        }
    }
}

private val CellMinSize = 110.dp
private val CellSpacing = 2.dp
private val CornerShape = 8.dp

/** Rows of cells fetched ahead of the viewport edge, so scrolling does not hit a blank tail. */
private const val LOAD_AHEAD = 24

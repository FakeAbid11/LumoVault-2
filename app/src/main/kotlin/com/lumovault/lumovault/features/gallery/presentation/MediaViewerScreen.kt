package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.lumovault.lumovault.core.database.entity.MediaItemEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
    initialIndex: Int,
    onBack: () -> Unit,
    onFindSimilar: ((localId: String) -> Unit)? = null,
    /**
     * The collection to page through. Null means the main timeline; the flag
     * views pass their own list because an index into their grid is not an
     * index into the timeline — opening "photo 3 of Favorites" and landing on
     * the third timeline photo would be a silent wrong-photo bug.
     */
    items: List<MediaItemEntity>? = null,
    viewModel: GalleryViewModel = hiltViewModel(),
) {
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val source = items ?: timeline
    if (source.isEmpty()) {
        // The viewer is only reachable from a grid, so an empty list here
        // means the underlying data was cleared while the viewer was open.
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val index = initialIndex.coerceIn(0, source.lastIndex)
    val pagerState = rememberPagerState(initialPage = index) { source.size }
    var uiVisible by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            if (uiVisible) {
                TopAppBar(
                    title = { Text("${pagerState.currentPage + 1} / ${source.size}") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black.copy(alpha = 0.6f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White,
                    ),
                    actions = {
                        if (onFindSimilar != null) {
                            IconButton(onClick = { onFindSimilar(source[pagerState.currentPage].localId) }) {
                                Icon(Icons.Filled.ImageSearch, "Find similar")
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (uiVisible) {
                ViewerBottomBar(
                    item = source[pagerState.currentPage],
                    onToggleFavorite = { viewModel.toggleFavorite(it) },
                    onTrash = { viewModel.moveToTrash(it) },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.6f)),
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(padding),
        ) {
            HorizontalPager(state = pagerState) { page ->
                val item = source[page]
                ZoomableMedia(
                    item = item,
                    onTap = { uiVisible = !uiVisible },
                )
            }
        }
    }
}

@Composable
private fun ZoomableMedia(item: MediaItemEntity, onTap: () -> Unit) {
    val isVideo = item.mimeType.startsWith("video/")
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 5f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    if (scale <= 1f) onTap()
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        if (isVideo) {
            VideoPlayer(uri = item.filePath)
        } else {
            AsyncImage(
                model = item.filePath,
                contentDescription = item.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    .transformable(state = transformableState),
            )
        }
    }
}

@Composable
private fun VideoPlayer(uri: String) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = true
                this.player = player
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ViewerBottomBar(
    item: MediaItemEntity,
    onToggleFavorite: (MediaItemEntity) -> Unit,
    onTrash: (MediaItemEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onToggleFavorite(item) }) {
            Icon(
                if (item.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (item.isFavorite) "Unfavorite" else "Favorite",
                tint = if (item.isFavorite) Color.Red else Color.White,
            )
        }
        IconButton(onClick = { /* share — Phase 8 */ }) {
            Icon(Icons.Filled.Share, "Share", tint = Color.White)
        }
        IconButton(onClick = { onTrash(item) }) {
            Icon(Icons.Filled.Delete, "Delete", tint = Color.White)
        }
    }
}

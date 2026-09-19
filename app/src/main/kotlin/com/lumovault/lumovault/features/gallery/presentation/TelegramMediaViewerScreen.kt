package com.lumovault.lumovault.features.gallery.presentation

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.service.GallerySaveService
import com.lumovault.lumovault.features.gallery.data.service.TelegramDownloadService
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelegramMediaViewerScreen(
    items: List<MediaItemEntity>,
    initialIndex: Int,
    onBack: () -> Unit,
    downloader: TelegramDownloadService,
    gallerySaveService: GallerySaveService,
    storageChannelId: Long,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0))) { items.size }

    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    DisposableEffect(Unit) {
        onDispose { downloader.cancelAll() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val page = pagerState.currentPage.coerceIn(items.indices)
                    Text(items.getOrNull(page)?.let { it.fileName ?: it.displayName } ?: "")
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val item = items.getOrNull(pagerState.currentPage) ?: return@IconButton
                        val messageId = item.telegramMessageId?.toLongOrNull() ?: return@IconButton
                        val taskId = "${storageChannelId}_$messageId"
                        downloadProgress = 0f

                        scope.launch {
                            try {
                                val result = downloader.downloadFile(
                                    chatId = storageChannelId,
                                    messageId = messageId,
                                    taskId = taskId,
                                )
                                if (result != null) {
                                    val file = java.io.File(result.filePath)
                                    val mimeType = item.mimeType ?: "image/*"
                                    if (mimeType.startsWith("video/")) {
                                        gallerySaveService.saveVideo(file)
                                    } else {
                                        gallerySaveService.saveImage(file)
                                    }
                                    Toast.makeText(context, "Saved to gallery", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Throwable) {
                                Toast.makeText(context, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                            downloadProgress = null
                        }
                    }) {
                        Icon(Icons.Filled.CloudDownload, contentDescription = "Download to gallery")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.6f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val item = items[page]
                if (item.filePath.startsWith("telegram://")) {
                    TelegramThumbnail(item, downloader, storageChannelId)
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(item.filePath).crossfade(true).build(),
                        contentDescription = item.displayName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            AnimatedVisibility(
                visible = downloadProgress != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.7f), CircleShape)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LinearProgressIndicator(progress = { downloadProgress ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("Downloading... ${((downloadProgress ?: 0f) * 100).toInt()}%", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun TelegramThumbnail(
    item: MediaItemEntity,
    downloader: TelegramDownloadService,
    storageChannelId: Long,
) {
    val context = LocalContext.current
    var thumbnailUri by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(item.localId) {
        val messageId = item.telegramMessageId?.toLongOrNull() ?: return@LaunchedEffect
        val result = downloader.downloadFile(
            chatId = storageChannelId,
            messageId = messageId,
            taskId = "${storageChannelId}_${messageId}_thumb",
        )
        if (result != null) {
            thumbnailUri = "file://${result.filePath}"
        }
    }

    if (thumbnailUri != null) {
        AsyncImage(
            model = ImageRequest.Builder(context).data(thumbnailUri).crossfade(true).build(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

package com.lumovault.lumovault.features.gallery.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.gallery.data.service.DeviceFolder
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService

/**
 * Local screen: the device's media folders.
 *
 * Simplified port of lib/features/gallery/presentation/screens/local_screen.dart.
 * The original showed a full date-grouped grid of every device asset with
 * backup badges; the Kotlin rewrite already owns that view (TimelineScreen
 * over the scanned Room timeline), so this screen is the folders list — the
 * same data the albums tab's folder section uses — and taps delegate upward
 * via [onOpenFolder].
 *
 * Folders come straight from MediaStore through [MediaScannerService.listFolders]
 * rather than Room, so folders a backup scan never covered still appear.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalScreen(
    onBack: () -> Unit,
    onOpenFolder: (bucketId: String, name: String) -> Unit,
    scanner: MediaScannerService,
) {
    // Loaded once per entry into the screen; a failed query degrades to the
    // empty state (e.g. media permission revoked), matching the albums tab.
    val folders by produceState<List<DeviceFolder>?>(initialValue = null) {
        value = runCatching { scanner.listFolders() }.getOrDefault(emptyList())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.local_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        val list = folders
        when {
            list == null -> Unit // first load is fast enough to skip a spinner
            list.isEmpty() -> EmptyCollection(
                title = stringResource(R.string.local_empty_title),
                explanation = stringResource(R.string.local_empty_explanation),
                modifier = Modifier.padding(padding),
            )
            else -> LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
            ) {
                items(list, key = { it.bucketId }) { folder ->
                    ListItem(
                        headlineContent = { Text(folder.name) },
                        supportingContent = {
                            Text(stringResource(R.string.local_folder_count, folder.totalItems))
                        },
                        leadingContent = {
                            Icon(Icons.Default.Folder, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            onOpenFolder(folder.bucketId, folder.name)
                        },
                    )
                }
            }
        }
    }
}

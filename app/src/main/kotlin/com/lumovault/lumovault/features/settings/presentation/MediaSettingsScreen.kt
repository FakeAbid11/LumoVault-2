package com.lumovault.lumovault.features.settings.presentation

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumovault.lumovault.R
import com.lumovault.lumovault.features.settings.domain.model.GalleryFilterType
import com.lumovault.lumovault.features.settings.domain.model.GallerySortOrder

/**
 * Media settings — backup content, gallery sort/filter, upload batching.
 *
 * Ported from media_settings_screen.dart, minus the folder picker (no device
 * folder provider exists in this rewrite yet) and the trash/max-file-size
 * dialogs (trash retention lives on the Storage screen here).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Scaffold(
        topBar = { SettingsTopBar(R.string.media_title, onBack) },
    ) { padding ->
        LazyColumn(modifier = Modifier.padding(padding)) {
            item { SettingsSectionHeader(R.string.media_section_backup_content) }
            item {
                SettingsSwitchItem(
                    title = R.string.media_backup_photos,
                    subtitle = R.string.media_backup_photos_subtitle,
                    icon = Icons.Default.Photo,
                    checked = settings.backupPhotos,
                    onCheckedChange = { v -> viewModel.update { it.copy(backupPhotos = v) } },
                )
            }
            item {
                SettingsSwitchItem(
                    title = R.string.media_backup_videos,
                    subtitle = R.string.media_backup_videos_subtitle,
                    icon = Icons.Default.Videocam,
                    checked = settings.backupVideos,
                    onCheckedChange = { v -> viewModel.update { it.copy(backupVideos = v) } },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.media_section_sort_filter) }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        GallerySortOrder.newestFirst to R.string.media_sort_newest_first,
                        GallerySortOrder.oldestFirst to R.string.media_sort_oldest_first,
                        GallerySortOrder.nameAsc to R.string.media_sort_name_asc,
                        GallerySortOrder.sizeDesc to R.string.media_sort_size_desc,
                    ),
                    selected = settings.gallerySortOrder,
                    onSelect = { v -> viewModel.update { it.copy(gallerySortOrder = v) } },
                )
            }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        GalleryFilterType.all to R.string.media_filter_all,
                        GalleryFilterType.photosOnly to R.string.media_filter_photos,
                        GalleryFilterType.videosOnly to R.string.media_filter_videos,
                        GalleryFilterType.favoritesOnly to R.string.media_filter_favorites,
                    ),
                    selected = settings.galleryFilterType,
                    onSelect = { v -> viewModel.update { it.copy(galleryFilterType = v) } },
                )
            }

            item { HorizontalDivider() }
            item { SettingsSectionHeader(R.string.media_section_upload) }
            item {
                SettingsRadioGroup(
                    options = listOf(
                        5 to R.string.media_batch_5,
                        10 to R.string.media_batch_10,
                        25 to R.string.media_batch_25,
                        50 to R.string.media_batch_50,
                    ),
                    selected = settings.uploadBatchSize,
                    onSelect = { v -> viewModel.update { it.copy(uploadBatchSize = v) } },
                )
            }
        }
    }
}

package com.lumovault.lumovault.features.albums.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Streams one device folder's contents. The bucket id from the route is
 * resolved to the bucket name (what the media rows store as album_name),
 * falling back to the raw id when MediaStore no longer knows it.
 */
@HiltViewModel
class DeviceFolderViewModel @Inject constructor(
    private val mediaDao: MediaDao,
    private val scanner: MediaScannerService,
) : ViewModel() {

    private val _folderName = MutableStateFlow<String?>(null)
    val folderName: StateFlow<String?> = _folderName.asStateFlow()

    private val _items = MutableStateFlow<List<MediaItemEntity>>(emptyList())
    val items: StateFlow<List<MediaItemEntity>> = _items.asStateFlow()

    fun open(bucketId: String) {
        viewModelScope.launch {
            val name = scanner.bucketName(bucketId) ?: bucketId
            _folderName.value = name
            // Snapshot rather than a live flow: MediaStore pushes no change
            // signal we could cheaply observe, matching the Flutter screen's
            // load-once behavior.
            _items.value = mediaDao.byAlbum(name)
        }
    }
}
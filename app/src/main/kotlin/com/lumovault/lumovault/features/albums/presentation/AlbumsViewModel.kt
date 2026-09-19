package com.lumovault.lumovault.features.albums.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.lumovault.core.database.dao.AlbumDao
import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.AlbumEntity
import com.lumovault.lumovault.core.database.entity.AlbumItemEntity
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.service.DeviceFolder
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One row on the Albums screen: a user album with its live item count and
 * resolved cover URI (null → icon placeholder). */
data class AlbumWithCount(
    val album: AlbumEntity,
    val count: Int,
    val coverPath: String?,
)

/**
 * Backs the Albums tab and the album-detail route.
 *
 * Ported from lib/features/albums/presentation/screens/albums_screen.dart and
 * album_detail_screen.dart. The originals read three providers (albums list,
 * per-album counts, device folders) and degraded each independently — a
 * loading counts query still showed albums with zero badges. Here the albums
 * flow is the spine (it's a Room flow, so renames/creates re-emit on their
 * own) and counts + device folders refresh alongside it, so the screen never
 * has to wire three separate async states.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val albumDao: AlbumDao,
    private val mediaDao: MediaDao,
    private val scanner: MediaScannerService,
) : ViewModel() {

    private val _deviceFolders = MutableStateFlow<List<DeviceFolder>>(emptyList())
    val deviceFolders: StateFlow<List<DeviceFolder>> = _deviceFolders.asStateFlow()

    /** Albums joined to their item counts, position-ordered. */
    val albums: StateFlow<List<AlbumWithCount>> = albumDao.allAlbumsFlow()
        .flatMapLatest { list ->
            // Recompute counts whenever the album set changes. Item adds go
            // through [addToAlbum]/[removeFromAlbum] below, which refresh.
            val counts = albumDao.allAlbumCounts().associateBy({ it.albumId }, { it.count })
            kotlinx.coroutines.flow.flowOf(
                list.map { album ->
                    // cover_id references MediaItems.local_id; resolve it to the
                    // stored content URI so Coil can load it directly.
                    val cover = album.coverId?.let { mediaDao.byLocalId(it)?.filePath }
                    AlbumWithCount(album, counts[album.id] ?: 0, cover)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The items of the album currently open in detail view. album_items has
     * no Room flow, so this is refreshed explicitly after every mutation. */
    private val _openAlbumItems = MutableStateFlow<List<MediaItemEntity>>(emptyList())
    val openAlbumItems: StateFlow<List<MediaItemEntity>> = _openAlbumItems.asStateFlow()

    private var openAlbumId: Long? = null

    private val _openAlbum = MutableStateFlow<AlbumEntity?>(null)
    val openAlbum: StateFlow<AlbumEntity?> = _openAlbum.asStateFlow()

    init {
        refreshFolders()
    }

    /** Device folders straight from MediaStore, so every folder with media
     * appears — not just the ones a backup scan covered. */
    fun refreshFolders() = viewModelScope.launch {
        _deviceFolders.value = runCatching { scanner.listFolders() }.getOrDefault(emptyList())
    }

    fun openAlbum(albumId: Long) = viewModelScope.launch {
        openAlbumId = albumId
        _openAlbum.value = albumDao.albumById(albumId)
        _openAlbumItems.value = albumDao.itemsForAlbum(albumId)
    }

    fun clearOpenAlbum() {
        openAlbumId = null
        _openAlbum.value = null
        _openAlbumItems.value = emptyList()
    }

    private suspend fun refreshOpenAlbum() {
        openAlbumId?.let { _openAlbumItems.value = albumDao.itemsForAlbum(it) }
    }

    fun createAlbum(name: String) = viewModelScope.launch {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return@launch
        val now = System.currentTimeMillis()
        albumDao.createAlbum(AlbumEntity(name = trimmed, createdAt = now, updatedAt = now))
    }

    fun renameAlbum(albumId: Long, newName: String) = viewModelScope.launch {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return@launch
        albumDao.renameAlbum(albumId, trimmed, System.currentTimeMillis())
        _openAlbum.value = albumDao.albumById(albumId)
    }

    fun deleteAlbum(albumId: Long) = viewModelScope.launch {
        albumDao.deleteAlbumItems(albumId)
        albumDao.deleteAlbum(albumId)
    }

    fun addToAlbum(albumId: Long, mediaIds: List<String>) = viewModelScope.launch {
        val now = System.currentTimeMillis()
        albumDao.addToAlbumBatch(mediaIds.map { AlbumItemEntity(albumId = albumId, mediaId = it, addedAt = now) })
        albumDao.updateAutoCover(albumId, now)
        refreshOpenAlbum()
    }

    fun removeFromAlbum(albumId: Long, mediaId: String) = viewModelScope.launch {
        albumDao.removeFromAlbum(albumId, mediaId)
        albumDao.updateAutoCover(albumId, System.currentTimeMillis())
        refreshOpenAlbum()
    }
}

package com.lumovault.lumovault.features.gallery.data.repository

import com.lumovault.lumovault.core.database.dao.MediaDao
import com.lumovault.lumovault.core.database.entity.MediaItemEntity
import com.lumovault.lumovault.features.gallery.data.service.MediaScannerService
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Write-through gallery repository: MediaStore is the source of truth for what
 * exists, Room is the read model the UI observes.
 */
@Singleton
class GalleryRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val scanner: MediaScannerService,
) {

    val timeline: Flow<List<MediaItemEntity>> = mediaDao.timelineFlow()
    val favorites: Flow<List<MediaItemEntity>> = mediaDao.favoritesFlow()
    val trashed: Flow<List<MediaItemEntity>> = mediaDao.trashedFlow()

    suspend fun mediaItem(localId: String): MediaItemEntity? = mediaDao.byLocalId(localId)

    /**
     * Scan the device and sync Room to it.
     *
     * Rows absent from MediaStore are removed — a photo deleted from the
     * device gallery should leave the timeline. This is only safe for a full
     * unfiltered scan (the Flutter scanner deliberately suppresses deletion
     * when a folder filter is active, since the scan never saw the excluded
     * folders and would wrongly purge them).
     */
    suspend fun refreshFromDevice(onProgress: ((Int) -> Unit)? = null): Int {
        val now = System.currentTimeMillis()

        // listAll paginates internally and reports progress as it goes.
        val scanned = scanner.listAll { loaded -> onProgress?.invoke(loaded) }

        val entities = scanned.map { scanner.toEntity(it, now) }

        // Preserve existing PKs so the upsert targets the right row instead of
        // colliding on the localId unique index.
        val existing = mediaDao.byLocalIds(scanned.map { it.id.toString() })
            .associateBy { it.localId }
        val withIds = entities.map { e -> existing[e.localId]?.let { e.copy(id = it.id) } ?: e }

        mediaDao.upsertAll(withIds)

        // Remove rows whose media no longer exists on the device, chunked so
        // the IN clause stays bounded for very large libraries.
        val seen = withIds.map { it.localId }.toHashSet()
        val stale = existing.keys - seen
        stale.chunked(BATCH).forEach { mediaDao.deleteByLocalIds(it) }

        return withIds.size
    }

    suspend fun setFavorite(localId: String, favorite: Boolean) =
        mediaDao.setFavorite(localId, favorite)

    suspend fun moveToTrash(localId: String) =
        mediaDao.moveToTrash(localId, System.currentTimeMillis())

    suspend fun restoreFromTrash(localId: String) = mediaDao.restoreFromTrash(localId)

    companion object {
        private const val BATCH = 500
    }
}

package com.lumovault.app.data.repository

import androidx.room.withTransaction
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.mediastore.MediaStoreDataSource
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Keeps the Room index in step with MediaStore.
 *
 * A sync tags every row it saw with a scan id, upserts them, then deletes rows whose tag is
 * older. That is what makes removal detectable without a `NOT IN (…)` list — a library of tens of
 * thousands of items would blow past SQLite's variable limit — and without clearing the table,
 * which would throw away the timeline on every refresh.
 */
class MediaRepositoryImpl(
    private val database: LumoVaultDatabase,
    private val dao: MediaDao,
    private val source: MediaStoreDataSource,
) : MediaRepository {
    override fun observeWindow(limit: Int): Flow<List<Media>> =
        dao.observeWindow(limit).map { rows -> rows.map(MediaEntity::toMedia) }

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override fun observeCountByType(): Flow<Map<MediaType, Int>> =
        dao.observeTypeCounts().map { rows ->
            rows.associate { row -> MediaType.fromStorageKey(row.mediaType) to row.itemCount }
        }

    override fun observeFolders(): Flow<List<String>> =
        dao.observeFolders().map { paths -> paths.map(String::displayFolder).sorted() }

    override suspend fun sync(): SyncResult {
        val scanId = System.currentTimeMillis()
        val scanned = source.scan(scanId)

        return database.withTransaction {
            // Chunked so one sync cannot hold the write transaction long enough to starve the
            // timeline query of a scroll in progress.
            scanned.chunked(UPSERT_CHUNK).forEach { dao.upsertAll(it) }
            SyncResult(indexed = scanned.size, removed = dao.pruneBefore(scanId))
        }
    }

    override suspend fun clear() {
        database.withTransaction { dao.clear() }
    }

    private companion object {
        const val UPSERT_CHUNK = 500
    }
}

private fun MediaEntity.toMedia() = Media(
    id = mediaStoreId,
    contentUri = contentUri,
    type = MediaType.fromStorageKey(mediaType),
    mimeType = mimeType,
    displayName = displayName,
    relativePath = relativePath,
    sizeBytes = sizeBytes,
    dateAddedSeconds = dateAddedSeconds,
    dateModifiedSeconds = dateModifiedSeconds,
    width = width,
    height = height,
    durationMillis = durationMillis,
)

/** `DCIM/Camera/` reads as `DCIM/Camera`; an empty path is the media root. */
private fun String.displayFolder(): String = trim('/', '\\').ifBlank { MEDIA_ROOT_LABEL }

private const val MEDIA_ROOT_LABEL = "/"

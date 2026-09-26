package com.lumovault.app.data.repository

import androidx.room.withTransaction
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.data.local.mediastore.MediaStoreDataSource
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.metadata.MediaMetadataDao
import com.lumovault.app.data.local.AppSettingsStore
import com.lumovault.app.data.local.organization.MediaOrganizationDao
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.SyncResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps the Room index in step with MediaStore.
 *
 * A sync tags every row it saw with a scan id, upserts them, then deletes rows whose tag is older. That
 * is what makes removal detectable without a `NOT IN (…)` list — a library of tens of thousands of items
 * would blow past SQLite's variable limit — and without clearing the table, which would throw away the
 * timeline on every refresh.
 *
 * The prune is also the moment the app learns that a file left the device for good, so the two
 * organisation sweeps run in the same transaction: a favourite or an album membership for a MediaStore
 * id that no longer resolves is invisible to every query but never stops accumulating.
 */
class MediaRepositoryImpl(
    private val database: LumoVaultDatabase,
    private val dao: MediaDao,
    private val source: MediaStoreDataSource,
    private val organization: MediaOrganizationDao,
    private val albums: AlbumDao,
    private val metadata: MediaMetadataDao,
    private val settings: AppSettingsStore,
    private val nowSeconds: () -> Long = System::currentTimeMillis,
) : MediaRepository {

    /**
     * One scan at a time, for the whole process.
     *
     * A sync stamps the rows it saw and then deletes everything stamped older, so two scans in flight are
     * not merely wasteful: the later one prunes rows the earlier one has already upserted but not yet
     * finished writing, and the index quietly loses items that exist on the device. Nothing stopped that
     * while a scan could only be started by somebody in the foreground; a background pass now starts one on
     * a schedule, on a save, and on every retry in between, so the overlap is a case that will occur.
     *
     * Serialising is safe rather than merely defensive — a second scan of the same MediaStore content writes
     * the same rows, and it is the prune that must not run twice at once.
     */
    private val syncLock = Mutex()

    override fun observeWindow(limit: Int): Flow<List<Media>> =
        dao.observeWindow(limit).map { rows -> rows.map(MediaEntity::toMedia) }

    override fun observeCount(): Flow<Int> = dao.observeCount()

    override fun observeCountByType(): Flow<Map<MediaType, Int>> =
        dao.observeTypeCounts().map { rows ->
            rows.associate { row -> MediaType.fromStorageKey(row.mediaType) to row.itemCount }
        }

    override fun observeFolders(): Flow<List<String>> =
        dao.observeFolders().map { paths -> paths.map(String::displayFolder).sorted() }

    override suspend fun sync(): SyncResult = syncLock.withLock {
        val scanId = System.currentTimeMillis()
        val scanned = source.scan(scanId)

        database.withTransaction {
            // Chunked so one sync cannot hold the write transaction long enough to starve the
            // timeline query of a scroll in progress.
            scanned.chunked(UPSERT_CHUNK).forEach { dao.upsertAll(it) }
            val removed = dao.pruneBefore(scanId)
            organization.cleanupOrphans()
            albums.cleanupOrphanMemberships()
            // EXIF belongs to the file, not to a row that happened to survive a scan. A position read out
            // of a photograph nobody still has is worth nothing and cannot be re-derived, so it leaves with
            // the row that described it; without this sweep the map quietly keeps markers for deleted photos
            // until the next time somebody notices one.
            metadata.cleanupOrphans()
            // Stamped inside the same transaction as the prune, so "last scan" cannot name a scan whose
            // rows never landed. Diagnostics asks this question, and a timestamp written a moment later by a
            // second statement could be true while the index it describes was not.
            settings.update { current -> current.copy(lastScanSeconds = nowSeconds()) }
            SyncResult(indexed = scanned.size, removed = removed)
        }
    }

    override suspend fun local(mediaStoreId: Long): Media? = dao.rowFor(mediaStoreId)?.toMedia()

    override suspend fun clear() {
        database.withTransaction { dao.clear() }
    }

    private companion object {
        const val UPSERT_CHUNK = 500
    }
}

/** `DCIM/Camera/` reads as `DCIM/Camera`; an empty path is the media root. */
private fun String.displayFolder(): String = trim('/', '\\').ifBlank { MEDIA_ROOT_LABEL }

private const val MEDIA_ROOT_LABEL = "/"

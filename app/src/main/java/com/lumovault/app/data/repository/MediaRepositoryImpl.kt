package com.lumovault.app.data.repository

import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.data.local.mediastore.MediaIndexScan
import com.lumovault.app.data.local.mediastore.MediaIndexSource
import com.lumovault.app.data.local.mediastore.prunesIndex
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.metadata.MediaMetadataDao
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
 * A sync tags every row it saw with a scan id, upserts them, then deletes rows whose tag is older. That is what
 * makes removal detectable without a `NOT IN (…)` list — a library of tens of thousands of items would blow past
 * SQLite's variable limit — and without clearing the table, which would throw away the timeline on every refresh.
 *
 * The prune is also the moment the app learns that a file left the device for good, so the two organisation
 * sweeps run in the same transaction: a favourite or an album membership for a MediaStore id that no longer
 * resolves is invisible to every query but never stops accumulating.
 *
 * Both of those facts are why an inconclusive scan stops here rather than syncing. The sweeps are written as
 * `NOT IN (SELECT media_store_id FROM media)`, which over an emptied `media` is true of every row they own — so
 * a scan that did not answer would take the user's favourites, archive marks, trash timestamps, album
 * memberships and every GPS position with it, in one commit, permanently. Nothing downstream can undo it, because
 * none of it is derivable from MediaStore.
 */
internal class MediaRepositoryImpl(
    private val dao: MediaDao,
    private val source: MediaIndexSource,
    private val organization: MediaOrganizationDao,
    private val albums: AlbumDao,
    private val metadata: MediaMetadataDao,
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    /**
     * Stamps the settings row, from inside the transaction the prune ran in.
     *
     * A callback rather than the settings store so the ordering is kept by the caller: "last scan" has to name
     * an index the caller can already see, and a pass that was refused by the provider must not leave a stamp
     * behind claiming it was not.
     */
    private val stampScan: suspend () -> Unit,
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
        val scan = source.scan(scanId)

        // Decided before anything is written, and it is the only guard the destructive half of this function
        // has. The scan id is generated but unused on this path, so the previous pass's tags are left exactly
        // as they were and the next period is an ordinary scan rather than a recovery from a wipe.
        if (!scan.prunesIndex()) return@withLock SyncResult(indexed = 0, removed = 0, reconciled = false)

        val rows = (scan as? MediaIndexScan.Found)?.rows.orEmpty()
        var removed = 0

        inTransaction {
            // Chunked so one sync cannot hold the write transaction long enough to starve the
            // timeline query of a scroll in progress.
            rows.chunked(UPSERT_CHUNK).forEach { dao.upsertAll(it) }
            removed = dao.pruneBefore(scanId)
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
            stampScan()
        }

        SyncResult(indexed = rows.size, removed = removed)
    }

    override suspend fun local(mediaStoreId: Long): Media? = dao.rowFor(mediaStoreId)?.toMedia()

    override suspend fun clear() {
        inTransaction { dao.clear() }
    }

    private companion object {
        const val UPSERT_CHUNK = 500
    }
}

/** `DCIM/Camera/` reads as `DCIM/Camera`; an empty path is the media root. */
private fun String.displayFolder(): String = trim('/', '\\').ifBlank { MEDIA_ROOT_LABEL }

private const val MEDIA_ROOT_LABEL = "/"

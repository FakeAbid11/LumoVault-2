package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/**
 * The local media library. The UI only ever sees this — not MediaStore, not Room, not cursors.
 *
 * Reads are windowed on purpose: a device can hold tens of thousands of items, and the timeline
 * should grow as the user scrolls instead of materialising the whole library up front.
 */
interface MediaRepository {
    /** Newest-first window of at most [limit] items. Re-queries when the index changes. */
    fun observeWindow(limit: Int): Flow<List<Media>>

    /** Total indexed items, so the UI can say whether more exist beyond the window. */
    fun observeCount(): Flow<Int>

    fun observeCountByType(): Flow<Map<MediaType, Int>>

    /** Distinct folders in the index, for the backup-source picker. */
    fun observeFolders(): Flow<List<String>>

    /**
     * Synchronises the index with MediaStore: new rows inserted, changed rows updated, rows that
     * no longer exist removed. Idempotent, and never a wipe-and-rebuild.
     *
     * Returns the number of items indexed. Does not hash file contents — that is the backup
     * engine's job in Phase 6.
     */
    suspend fun sync(): SyncResult

    /** Clears the index; used when media access is revoked, so stale rows are not shown. */
    suspend fun clear()
}

data class SyncResult(
    val indexed: Int,
    val removed: Int,
)

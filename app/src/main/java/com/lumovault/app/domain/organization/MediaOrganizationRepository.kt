package com.lumovault.app.domain.organization

import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.SystemAlbum
import kotlinx.coroutines.flow.Flow

/**
 * What the user decided about their own media: favourite, archived, in Trash.
 *
 * Nothing here reaches Telegram, and nothing here can. The queue is a different table with a different
 * owner, and an organisation action writes no row into it — which is the concrete form of PRD section
 * 12's rule that a favourite must not cause a duplicate upload. An item that is already on its way to
 * the channel stays exactly there when it is archived; the two facts are about different things.
 *
 * Every mutation takes a collection because every caller is a multi-select, and it is applied one id at
 * a time in the implementation: one statement per item, each touching only its own column, is what makes
 * "favouriting cannot disturb the archive state" true even when two actions overlap.
 */
interface MediaOrganizationRepository {
    /** Counts for all eight system albums, from one database read. */
    fun observeCounts(): Flow<SystemAlbumCounts>

    /**
     * An album's contents, newest first, bounded by [limit] the same way the Photos timeline is — a
     * library of 100,000 screenshots must not be materialised to draw a screenful of them.
     */
    fun observeContents(album: SystemAlbum, limit: Int): Flow<List<Media>>

    /** Which of [mediaStoreIds] are favourited, for the badge on a grid cell. */
    fun observeFavoritesWithin(mediaStoreIds: Collection<Long>): Flow<Set<Long>>

    suspend fun setFavorite(mediaStoreIds: Collection<Long>, favorite: Boolean)

    suspend fun setArchived(mediaStoreIds: Collection<Long>, archived: Boolean)

    /**
     * Hides items behind Trash without touching the file.
     *
     * This is the only thing "delete" means in Phase 7. The bytes are still on the device and the backup
     * is still in the channel; what changed is that the library stopped showing them, which is exactly
     * reversible and cannot lose anything.
     */
    suspend fun moveToTrash(mediaStoreIds: Collection<Long>)

    /** Returns how many items came back out. */
    suspend fun restoreFromTrash(mediaStoreIds: Collection<Long>): Int

    /** Every trashed id, which is what "Empty Trash" works over. */
    suspend fun trashedMediaIds(): List<Long>

    suspend fun trashedCount(): Int

    /**
     * Forgets items whose file the device has confirmed as deleted.
     *
     * Deliberately not callable before that confirmation, and the name says so: removing the
     * organisation of a file that is still on the device would put it back in the timeline, which is the
     * opposite of what the user asked for. Nothing in this path can reach Telegram — a local deletion
     * leaves the backup alone, and PRD section 72 treats that as the point rather than an oversight.
     */
    suspend fun forgetDeletedLocally(mediaStoreIds: Collection<Long>)
}

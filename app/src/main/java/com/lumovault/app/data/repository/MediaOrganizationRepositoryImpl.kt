package com.lumovault.app.data.repository

import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.organization.MediaOrganizationDao
import com.lumovault.app.data.local.organization.SystemAlbumDao
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import com.lumovault.app.domain.organization.SystemAlbumCounts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * The organisation record: what the user marked, archived, or threw away.
 *
 * Reads that answer a question about a *collection* go to [SystemAlbumDao], which derives each system
 * album from the media and organisation rows at query time; writes go to [MediaOrganizationDao], one
 * statement per item. That split is what makes the independence guarantee structural rather than a
 * convention: no write here reads the columns it is not updating, so an action the user did not take
 * cannot be the reason a mark disappeared.
 */
class MediaOrganizationRepositoryImpl(
    private val organization: MediaOrganizationDao,
    private val systemAlbums: SystemAlbumDao,
    private val media: MediaDao,
    private val albums: AlbumDao,
    private val nowSeconds: () -> Long,
    /**
     * Runs [forgetDeletedLocally]'s three writes as one statement group. Supplied rather than reached for
     * because the alternative was a database handle in a class whose decisions are testable without one;
     * the container wires it to `RoomDatabase.withTransaction`.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit,
    private val recentlyAddedWindowSeconds: Long = RECENTLY_ADDED_WINDOW_SECONDS,
) : MediaOrganizationRepository {

    override fun observeCounts(): Flow<SystemAlbumCounts> = flow {
        // Built per collection rather than once at construction: "recently" is a moving window, and a
        // figure computed when the app started would be quietly wrong by lunchtime.
        val since = nowSeconds() - recentlyAddedWindowSeconds
        emitAll(
            systemAlbums.observeCounts(since).map { row ->
                SystemAlbumCounts(
                    mapOf(
                        SystemAlbum.Camera to row.camera,
                        SystemAlbum.Screenshots to row.screenshots,
                        SystemAlbum.Downloads to row.downloads,
                        SystemAlbum.Videos to row.videos,
                        SystemAlbum.Favorites to row.favorites,
                        SystemAlbum.Archive to row.archived,
                        SystemAlbum.Trash to row.trashed,
                        SystemAlbum.RecentlyAdded to row.recentlyAdded,
                    ),
                )
            },
        )
    }

    override fun observeContents(album: SystemAlbum, limit: Int): Flow<List<Media>> {
        // `checkNotNull` rather than a silent fallback: [SystemAlbum] defines exactly one way to be each
        // of these albums, and an album that reached this branch without one is a new case that has to
        // fail loudly in a test instead of showing an empty screen on a device.
        val rows: Flow<List<MediaEntity>> = when (album) {
            SystemAlbum.Favorites -> systemAlbums.observeFavorites(limit)
            SystemAlbum.Archive -> systemAlbums.observeArchived(limit)
            SystemAlbum.Trash -> systemAlbums.observeTrashed(limit).map { list -> list.map { it.media } }
            SystemAlbum.Videos ->
                systemAlbums.observeByType(checkNotNull(album.mediaType).storageKey, limit)
            SystemAlbum.Camera, SystemAlbum.Screenshots, SystemAlbum.Downloads ->
                systemAlbums.observeByPath(checkNotNull(album.pathLikePattern), limit)
            SystemAlbum.RecentlyAdded ->
                systemAlbums.observeRecentlyAdded(nowSeconds() - recentlyAddedWindowSeconds, limit)
        }
        return rows.map { list -> list.map(MediaEntity::toMedia) }
    }

    override fun observeFavoritesWithin(mediaStoreIds: Collection<Long>): Flow<Set<Long>> {
        // Guarded because an empty `IN ()` is not valid SQL, and a window with nothing in it yet is an
        // ordinary first frame on an empty device rather than an error.
        if (mediaStoreIds.isEmpty()) return flowOf(emptySet())
        return organization.observeFavoritesIn(mediaStoreIds).map { it.toSet() }
    }

    override suspend fun setFavorite(mediaStoreIds: Collection<Long>, favorite: Boolean) {
        mediaStoreIds.forEach { organization.setFavorite(it, favorite) }
    }

    override suspend fun setArchived(mediaStoreIds: Collection<Long>, archived: Boolean) {
        mediaStoreIds.forEach { organization.setArchived(it, archived) }
    }

    override suspend fun moveToTrash(mediaStoreIds: Collection<Long>) {
        val moment = nowSeconds()
        mediaStoreIds.forEach { organization.setTrashedAt(it, moment) }
    }

    override suspend fun restoreFromTrash(mediaStoreIds: Collection<Long>): Int =
        mediaStoreIds.sumOf { organization.restore(it) }

    override suspend fun trashedMediaIds(): List<Long> = organization.trashedIds()

    override suspend fun trashedCount(): Int = organization.trashedCount()

    /**
     * Clears the local rows for a file the device confirmed as deleted.
     *
     * `backup_queue` is deliberately absent. A local file leaving the phone says nothing about the copy
     * in the user's channel — PRD section 72 treats "local + cloud becomes cloud only" as the intended
     * outcome, and the Phase 6 record is what still resolves that photo to its message. The media row
     * does go, because the thing it described is gone; the organisation goes with it, because there is
     * nothing left to favourite, archive or restore.
     */
    override suspend fun forgetDeletedLocally(mediaStoreIds: Collection<Long>) {
        if (mediaStoreIds.isEmpty()) return
        inTransaction {
            organization.clearFor(mediaStoreIds)
            albums.removeFromEveryAlbum(mediaStoreIds)
            media.deleteByIds(mediaStoreIds)
        }
    }

    companion object {
        /**
         * How far back Recently Added reaches. Long enough to cover the imports a user actually notices
         * — a restored backup, a transferred phone, a month's camera roll — and short enough that the
         * view is a list rather than the library again.
         */
        const val RECENTLY_ADDED_WINDOW_SECONDS = 30L * 24 * 60 * 60
    }
}

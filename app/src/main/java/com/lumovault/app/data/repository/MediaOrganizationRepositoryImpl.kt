package com.lumovault.app.data.repository

import com.lumovault.app.data.local.MAX_IDS_PER_QUERY
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.organization.MediaOrganizationDao
import com.lumovault.app.data.local.organization.LocalFolderRow
import com.lumovault.app.data.local.organization.SystemAlbumDao
import com.lumovault.app.domain.model.FolderPaths
import com.lumovault.app.domain.model.LocalFolderAlbum
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import com.lumovault.app.domain.organization.SystemAlbumCounts
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    override fun observeLocalFolders(): Flow<List<LocalFolderAlbum>> =
        systemAlbums.observeFolderAlbums().map { rows -> localFolderAlbums(rows) }

    override fun observeLocalFolderContents(relativePath: String, limit: Int): Flow<List<Media>> =
        systemAlbums.observeFolderContents(FolderPaths.normalize(relativePath), limit)
            .map { list -> list.map(MediaEntity::toMedia) }

    override fun observeFavoritesWithin(mediaStoreIds: Collection<Long>): Flow<Set<Long>> {
        // Guarded because an empty `IN ()` is not valid SQL, and a window with nothing in it yet is an
        // ordinary first frame on an empty device rather than an error.
        val chunks = mediaStoreIds.chunked(MAX_IDS_PER_QUERY)
        if (chunks.isEmpty()) return flowOf(emptySet())
        if (chunks.size == 1) return organization.observeFavoritesIn(chunks[0]).map { it.toSet() }
        // A loaded window outgrows SQLite's parameter ceiling after a few screens of scrolling, so the
        // window is asked in pieces and the pieces merged — `combine` because Room re-emits one chunk at
        // a time and the screen wants the whole answer.
        return combine(chunks.map { organization.observeFavoritesIn(it) }) { parts -> parts.flatMap { it }.toSet() }
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
            // Chunked *inside* the transaction, not before it: "empty the Trash" hands this every trashed
            // id at once, and SQLite's ceiling on bind parameters is a property of the statement rather than
            // of how the list was built. Keeping one transaction means a library of any size still clears a
            // row, its organisation and its memberships together.
            mediaStoreIds.chunked(MAX_IDS_PER_QUERY).forEach { chunk ->
                organization.clearFor(chunk)
                albums.removeFromEveryAlbum(chunk)
                media.deleteByIds(chunk)
            }
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

/**
 * Rows in, albums out.
 *
 * Three decisions the query cannot make. Normalization, because MediaStore's spelling of a path is not a
 * stable identity and `Pictures/WhatsApp` would otherwise be a different folder from `Pictures/WhatsApp/`,
 * each with its own card and its own half of the count. Deduplication against the system albums, because
 * `DCIM/Camera/` is already on screen as Camera and showing it twice is the bug this feature would
 * otherwise introduce. And naming, because the last segment is what a person calls the folder while the
 * whole path is what keeps `Pictures/Telegram/` and `DCIM/Telegram/` apart.
 *
 * A folder with nothing in it cannot appear, because the query groups over media rows — so the exclusion
 * rule below has to be the thing that removes a folder from the list, and a card that would show zero is
 * never left behind.
 */
internal fun localFolderAlbums(rows: List<LocalFolderRow>): List<LocalFolderAlbum> {
    val counts = LinkedHashMap<String, Int>()
    val covers = LinkedHashMap<String, String?>()

    for (row in rows) {
        val path = FolderPaths.normalize(row.relativePath)
        if (path == FolderPaths.ROOT) continue
        if (SystemAlbum.entries.any { album -> album.covers(path) }) continue

        counts[path] = (counts[path] ?: 0) + row.itemCount
        if (!covers.containsKey(path)) covers[path] = row.coverUri
    }

    return counts.map { (path, count) ->
        LocalFolderAlbum(
            relativePath = path,
            displayName = FolderPaths.displayNameOf(path),
            parentLabel = FolderPaths.parentOf(path),
            mediaCount = count,
            coverUri = covers[path],
        )
    }.sortedWith(
        compareByDescending<LocalFolderAlbum> { it.mediaCount }
            .thenBy { it.displayName.lowercase() }
            .thenBy { it.relativePath },
    )
}

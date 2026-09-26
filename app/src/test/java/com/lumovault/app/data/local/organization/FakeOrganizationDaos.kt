package com.lumovault.app.data.local.organization

import com.lumovault.app.data.local.media.LocalNameMatch
import com.lumovault.app.data.local.media.MediaDao
import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.media.MediaTypeCount
import com.lumovault.app.data.local.organization.LocalFolderRow
import com.lumovault.app.domain.model.SystemAlbum
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * `media`, in memory, with the timeline filter mirrored.
 *
 * The filter is the part worth having right: "archived and trashed items leave Photos" is a `WHERE`
 * clause, and if the fake filtered in the caller instead, the repository tests would pass while the query
 * showed everything.
 */
class FakeMediaDao(private val store: OrganizationStore) : MediaDao {
    /** One index row by id — the lookup a restore makes after its scan, and Free Up Space before a delete. */
    override suspend fun rowFor(id: Long): MediaEntity? = store.media[id]

    override fun observeWindow(limit: Int): Flow<List<MediaEntity>> = store.tick.map {
        store.media.values.filter { row -> store.visible(row.mediaStoreId) }
            .sortedWith(
                compareByDescending<MediaEntity> { it.dateAddedSeconds }.thenByDescending { it.mediaStoreId },
            )
            .take(limit)
    }

    override fun observeCount(): Flow<Int> = store.tick.map { store.media.values.count { visible(it) } }

    override fun observeTypeCounts(): Flow<List<MediaTypeCount>> = store.tick.map {
        store.media.values.filter { visible(it) }
            .groupingBy { it.mediaType }
            .eachCount()
            .map { (mediaType, itemCount) -> MediaTypeCount(mediaType, itemCount) }
    }

    override fun observeFolders(): Flow<List<String>> = store.tick.map {
        store.media.values.map { row -> row.relativePath }.filter(String::isNotBlank).distinct().sorted()
    }

    override suspend fun currentCount(): Int = store.media.values.count { visible(it) }

    override suspend fun upsertAll(items: List<MediaEntity>) {
        // `INSERT OR REPLACE`, which rebuilds the row: the point of the fake being faithful here is that
        // organisation lives in *another* table, so a rewrite cannot reach it.
        items.forEach { store.media[it.mediaStoreId] = it }
        store.bump()
    }

    override suspend fun pruneBefore(scanId: Long): Int {
        val stale = store.media.values.filter { it.lastSeenScanId < scanId }.map { it.mediaStoreId }
        stale.forEach { store.media.remove(it) }
        store.bump()
        return stale.size
    }

    override suspend fun deleteByIds(ids: Collection<Long>): Int {
        val matching = ids.filter { store.media.containsKey(it) }
        matching.forEach { store.media.remove(it) }
        store.bump()
        return matching.size
    }

    override suspend fun clear() {
        store.media.clear()
        store.bump()
    }

    override suspend fun findByName(names: List<String>): List<LocalNameMatch> =
        store.media.values.filter { it.displayName in names }
            .map { LocalNameMatch(it.displayName, it.sizeBytes) }

    private fun visible(entity: MediaEntity) = store.visible(entity.mediaStoreId)
}

/**
 * The organisation record, writing one column per statement.
 *
 * A write that rebuilt the row wholesale would be indistinguishable in an assertion on the column it set
 * while silently clearing the other two, which is precisely the bug this shape of fake cannot hide.
 */
class FakeMediaOrganizationDao(private val store: OrganizationStore) : MediaOrganizationDao {
    override suspend fun setFavorite(id: Long, value: Boolean) = mutate(id) { it.copy(favorite = value) }

    override suspend fun setArchived(id: Long, value: Boolean) = mutate(id) { it.copy(archived = value) }

    override suspend fun setTrashedAt(id: Long, trashedAt: Long) =
        mutate(id) { it.copy(trashedAt = trashedAt) }

    override suspend fun restore(id: Long): Int {
        val existing = store.organization[id] ?: return 0
        store.organization[id] = existing.copy(trashedAt = 0L)
        store.bump()
        return 1
    }

    override suspend fun clearFor(ids: Collection<Long>): Int {
        val matching = ids.count { store.organization.containsKey(it) }
        ids.forEach { store.organization.remove(it) }
        store.bump()
        return matching
    }

    override suspend fun cleanupOrphans(): Int {
        val orphans = store.organization.keys.filterNot { store.media.containsKey(it) }
        orphans.forEach { store.organization.remove(it) }
        store.bump()
        return orphans.size
    }

    override suspend fun trashedCount(): Int = store.organization.values.count { it.trashedAt > 0L }

    override suspend fun trashedIds(): List<Long> =
        store.organization.values.filter { it.trashedAt > 0L }.map { it.mediaStoreId }

    override fun observeFavoritesIn(ids: Collection<Long>): Flow<List<Long>> = store.tick.map {
        store.organization.values.filter { row -> row.favorite && row.mediaStoreId in ids }
            .map { row -> row.mediaStoreId }
    }

    private fun mutate(id: Long, change: (MediaOrganizationEntity) -> MediaOrganizationEntity) {
        val existing = store.organization[id] ?: MediaOrganizationEntity(mediaStoreId = id)
        store.organization[id] = change(existing)
        store.bump()
    }
}

/** Albums and membership, including the composite-key dedup and the cascade that must not reach media. */
class FakeAlbumDao(private val store: OrganizationStore) : AlbumDao {
    var deleteCalls = 0
        private set

    override suspend fun insert(album: AlbumEntity): Long {
        val id = if (album.id == 0L) store.nextAlbumId() else album.id
        store.albums[id] = album.copy(id = id)
        store.bump()
        return id
    }

    override suspend fun album(id: Long): AlbumEntity? = store.albums[id]

    override suspend fun albumIds(): List<Long> = store.albums.keys.toList()

    override suspend fun rename(id: Long, name: String): Int {
        val existing = store.albums[id] ?: return 0
        store.albums[id] = existing.copy(name = name)
        store.bump()
        return 1
    }

    override suspend fun delete(id: Long): Int {
        deleteCalls += 1
        val existing = store.albums.remove(id) ?: return 0
        store.members.removeAll(store.members.filter { it.albumId == id }.toSet())
        store.bump()
        return if (existing.id == id) 1 else 0
    }

    override suspend fun addMembers(albumId: Long, ids: Collection<Long>, now: Long) {
        // Only ids the index holds, and the composite primary key means a second add is the same row.
        ids.filter { store.media.containsKey(it) }
            .forEach { store.members += AlbumKey(albumId, it) }
        store.bump()
    }

    override suspend fun countMembers(albumId: Long): Int = store.members.count { it.albumId == albumId }

    override suspend fun removeMembers(albumId: Long, ids: Collection<Long>): Int {
        val matching = store.members.filter { it.albumId == albumId && it.mediaStoreId in ids }.toSet()
        store.members.removeAll(matching)
        store.bump()
        return matching.size
    }

    override suspend fun removeFromEveryAlbum(ids: Collection<Long>): Int {
        val matching = store.members.filter { it.mediaStoreId in ids }.toSet()
        store.members.removeAll(matching)
        store.bump()
        return matching.size
    }

    override suspend fun membersWithin(albumId: Long, ids: Collection<Long>): List<Long> =
        ids.filter { AlbumKey(albumId, it) in store.members }

    override suspend fun cleanupOrphanMemberships(): Int {
        val orphans = store.members.filterNot { store.media.containsKey(it.mediaStoreId) }.toSet()
        store.members.removeAll(orphans)
        store.bump()
        return orphans.size
    }

    override suspend fun albumsContaining(mediaStoreId: Long): List<Long> =
        store.members.filter { it.mediaStoreId == mediaStoreId }
            // The join orders by the album's own recency, not by when the row was added — so the sheet
            // that ticks "already in" lists albums in the order the Albums screen shows them.
            .map { it.albumId }
            .sortedWith(
                compareByDescending<Long> { store.albums[it]?.createdAt ?: 0L }
                    .thenByDescending { it },
            )

    override fun observeAlbums(): Flow<List<AlbumListRow>> = store.tick.map {
        store.albums.values
            .sortedWith(compareByDescending<AlbumEntity> { it.createdAt }.thenByDescending { it.id })
            .map { it.row() }
    }

    override fun observeAlbum(id: Long): Flow<AlbumListRow?> = store.tick.map { store.albums[id]?.row() }

    override fun observeContent(albumId: Long, limit: Int): Flow<List<MediaEntity>> = store.tick.map {
        store.newestFirst(memberIds(albumId).filter(store::inAlbumView), limit)
    }

    private fun memberIds(albumId: Long): List<Long> =
        store.members.filter { it.albumId == albumId }.map { it.mediaStoreId }

    private fun AlbumEntity.row(): AlbumListRow {
        val visible = memberIds(id).filter(store::inAlbumView).mapNotNull { store.media[it] }
        val newest = visible.maxWithOrNull(
            compareBy<MediaEntity> { it.dateAddedSeconds }.thenBy { it.mediaStoreId },
        )
        return AlbumListRow(
            id = id,
            name = name,
            createdAt = createdAt,
            itemCount = visible.size,
            coverUri = newest?.contentUri,
        )
    }
}

/**
 * The system albums, derived from the same [SystemAlbum] definitions the SQL binds.
 *
 * Folder predicates go through [like], which interprets `%` and `_` the way SQLite does — so the pattern
 * a test checks is the pattern `MediaStore.RELATIVE_PATH` is compared against in production, not a
 * paraphrase of it.
 */
class FakeSystemAlbumDao(private val store: OrganizationStore) : SystemAlbumDao {
    override fun observeCounts(recentSince: Long): Flow<SystemAlbumCountsRow> = store.tick.map {
        val visible = store.media.values.filter { store.visible(it.mediaStoreId) }
        val organization = store.organization.values.filter { store.media.containsKey(it.mediaStoreId) }
        SystemAlbumCountsRow(
            camera = visible.count { it.relativePath.like(SystemAlbum.Camera.requirePattern()) },
            screenshots = visible.count { it.relativePath.like(SystemAlbum.Screenshots.requirePattern()) },
            downloads = visible.count { it.relativePath.like(SystemAlbum.Downloads.requirePattern()) },
            videos = visible.count { it.mediaType == SystemAlbum.Videos.requireType().storageKey },
            favorites = organization.count { it.favorite && it.trashedAt == 0L },
            archived = organization.count { it.archived && it.trashedAt == 0L },
            trashed = organization.count { it.trashedAt > 0L },
            recentlyAdded = visible.count { it.dateAddedSeconds >= recentSince },
        )
    }

    override fun observeFavorites(limit: Int): Flow<List<MediaEntity>> = observeOrganized(limit) { it.favorite }

    override fun observeArchived(limit: Int): Flow<List<MediaEntity>> = observeOrganized(limit) { it.archived }

    /**
     * Grouped exactly the way the SQL groups it: by the raw `RELATIVE_PATH`, counting what is not in
     * Trash — and, like the real query, leaving archived items in, because a folder album reports where the
     * files are.
     */
    override fun observeFolderAlbums(): Flow<List<LocalFolderRow>> = store.tick.map {
        store.media.values
            .filter { row -> row.relativePath.isNotEmpty() && store.inAlbumView(row.mediaStoreId) }
            .groupBy { row -> row.relativePath }
            .map { (path, rows) ->
                LocalFolderRow(
                    relativePath = path,
                    itemCount = rows.size,
                    coverUri = store.newestFirst(rows.map { it.mediaStoreId }, 1).firstOrNull()?.contentUri,
                )
            }
    }

    override fun observeFolderContents(relativePath: String, limit: Int): Flow<List<MediaEntity>> =
        store.tick.map {
            store.newestFirst(
                store.media.values
                    .filter { row -> row.relativePath == relativePath && store.inAlbumView(row.mediaStoreId) }
                    .map { row -> row.mediaStoreId },
                limit,
            )
        }

    override fun observeTrashed(limit: Int): Flow<List<TrashedMediaRow>> = store.tick.map {
        store.organization.values.filter { it.trashedAt > 0L }
            .sortedWith(
                compareByDescending<MediaOrganizationEntity> { it.trashedAt }.thenByDescending { it.mediaStoreId },
            )
            .take(limit)
            .mapNotNull { row -> store.media[row.mediaStoreId]?.let { TrashedMediaRow(it, row.trashedAt) } }
    }

    override fun observeByPath(pattern: String, limit: Int): Flow<List<MediaEntity>> = store.tick.map {
        store.newestFirst(
            store.media.values.filter { store.inAlbumView(it.mediaStoreId) && it.relativePath.like(pattern) }
                .map { it.mediaStoreId },
            limit,
        )
    }

    override fun observeByType(mediaType: String, limit: Int): Flow<List<MediaEntity>> = store.tick.map {
        store.newestFirst(
            store.media.values.filter {
                store.inAlbumView(it.mediaStoreId) && it.mediaType == mediaType
            }.map { it.mediaStoreId },
            limit,
        )
    }

    override fun observeRecentlyAdded(sinceSeconds: Long, limit: Int): Flow<List<MediaEntity>> =
        store.tick.map {
            store.newestFirst(
                store.media.values.filter {
                    store.inAlbumView(it.mediaStoreId) && it.dateAddedSeconds >= sinceSeconds
                }.map { it.mediaStoreId },
                limit,
            )
        }

    private fun observeOrganized(limit: Int, predicate: (MediaOrganizationEntity) -> Boolean) =
        store.tick.map {
            store.newestFirst(
                store.organization.values.filter { row ->
                    predicate(row) && row.trashedAt == 0L && store.media.containsKey(row.mediaStoreId)
                }.map { row -> row.mediaStoreId },
                limit,
            )
        }
}

private fun SystemAlbum.requirePattern(): String = checkNotNull(pathLikePattern)

private fun SystemAlbum.requireType(): com.lumovault.app.domain.model.MediaType = checkNotNull(mediaType)

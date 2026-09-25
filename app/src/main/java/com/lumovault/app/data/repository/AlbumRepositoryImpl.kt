package com.lumovault.app.data.repository

import com.lumovault.app.data.local.media.toMedia
import com.lumovault.app.data.local.organization.AlbumDao
import com.lumovault.app.data.local.organization.AlbumEntity
import com.lumovault.app.data.local.organization.AlbumListRow
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.organization.Album
import com.lumovault.app.domain.organization.AlbumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * User albums.
 *
 * The name rule is the only validation in the app that guards a piece of user text stored for its own
 * sake, and it belongs here rather than in a text field: an album called nothing but spaces, or four
 * thousand characters of pasted text, would be saved happily by SQLite and drawn badly forever.
 * Blank-after-trimming is refused, and [AlbumRepository.MAX_NAME_LENGTH] is applied by truncation rather
 * than refusal because a name typed out long is still a name the user chose.
 */
class AlbumRepositoryImpl(
    private val albums: AlbumDao,
    private val nowSeconds: () -> Long,
) : AlbumRepository {

    override fun observeAlbums(): Flow<List<Album>> =
        albums.observeAlbums().map { rows -> rows.map(AlbumListRow::toAlbum) }

    override fun observeAlbum(albumId: Long): Flow<Album?> =
        albums.observeAlbum(albumId).map { row -> row?.toAlbum() }

    override fun observeContents(albumId: Long, limit: Int): Flow<List<Media>> =
        albums.observeContent(albumId, limit).map { rows -> rows.map { it.toMedia() } }

    override suspend fun create(name: String): Long? {
        val clean = validName(name) ?: return null
        return albums.insert(AlbumEntity(name = clean, createdAt = nowSeconds())).takeIf { it != 0L }
    }

    override suspend fun rename(albumId: Long, name: String): Boolean {
        val clean = validName(name) ?: return false
        return albums.rename(albumId, clean) > 0
    }

    /**
     * Deletes the album and nothing else.
     *
     * There is no cascade to media, no statement touching `media_organization` or `backup_queue`, and no
     * Telegram call anywhere near this method — the only foreign key in play is membership rows to this
     * album, which is what `ON DELETE CASCADE` was declared for. An album is a way of looking at photos;
     * closing the list does not alter a single file.
     */
    override suspend fun delete(albumId: Long): Boolean = albums.delete(albumId) > 0

    override suspend fun addMedia(albumId: Long, mediaStoreIds: Collection<Long>): Int {
        if (mediaStoreIds.isEmpty()) return 0
        // Checked rather than trusted: `album_media` has a foreign key on `albums`, so a statement for an
        // album that does not exist would throw from the database instead of returning a number.
        if (albums.album(albumId) == null) return 0

        val before = albums.countMembers(albumId)
        albums.addMembers(albumId, mediaStoreIds, nowSeconds())
        // Room gives an `INSERT` no row count, so the difference is what "how many did you add" means —
        // and it excludes both the ids already in the album and any that are not in the index.
        return albums.countMembers(albumId) - before
    }

    override suspend fun removeMedia(albumId: Long, mediaStoreIds: Collection<Long>): Int {
        if (mediaStoreIds.isEmpty()) return 0
        return albums.removeMembers(albumId, mediaStoreIds)
    }

    override suspend fun membersWithin(albumId: Long, mediaStoreIds: Collection<Long>): List<Long> {
        if (mediaStoreIds.isEmpty()) return emptyList()
        return albums.membersWithin(albumId, mediaStoreIds)
    }

    override suspend fun albumsContaining(mediaStoreId: Long): List<Long> =
        albums.albumsContaining(mediaStoreId)

    private fun validName(raw: String): String? =
        raw.trim().takeIf { it.isNotEmpty() }?.take(AlbumRepository.MAX_NAME_LENGTH)

    private fun AlbumListRow.toAlbum() = Album(
        id = id,
        name = name,
        createdAtSeconds = createdAt,
        itemCount = itemCount,
        coverUri = coverUri,
    )
}

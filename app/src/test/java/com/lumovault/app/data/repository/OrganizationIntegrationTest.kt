package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.organization.FakeAlbumDao
import com.lumovault.app.data.local.organization.FakeMediaDao
import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.FakeSystemAlbumDao
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.SystemAlbum
import com.lumovault.app.domain.organization.AlbumRepository
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two organisation repositories over one index, which is where Phase 7's promises are actually kept
 * or broken.
 *
 * Each rule here is a statement about *tables*, not about methods: an album's count is a join, hiding an
 * archived item is a `WHERE` clause on a different table, and "organising does not upload" is true
 * because `backup_queue` is never opened by this path. So the tests drive the queries through the same
 * store a screen would, and assert the end state of the rows.
 */
class OrganizationIntegrationTest {
    private val store = OrganizationStore()
    private val media = FakeMediaDao(store)
    private val organization = FakeMediaOrganizationDao(store)
    private val albums = FakeAlbumDao(store)
    private val systemAlbums = FakeSystemAlbumDao(store)
    private val queue = FakeBackupQueueDao()

    private val albumRepository: AlbumRepository =
        AlbumRepositoryImpl(albums = albums, nowSeconds = { NOW })

    private val organizationRepository: MediaOrganizationRepository = MediaOrganizationRepositoryImpl(
        organization = organization,
        systemAlbums = systemAlbums,
        media = media,
        albums = albums,
        nowSeconds = { NOW },
        inTransaction = { block -> block() },
    )

    @Test
    fun aRescanThatRewritesARowKeepsEveryDecisionTheUserMade() = runBlocking {
        store.index(FakeMediaRow(1L))
        val albumId = requireNotNull(albumRepository.create("Trip"))
        albumRepository.addMedia(albumId, listOf(1L))
        organizationRepository.setFavorite(listOf(1L), true)
        organizationRepository.setArchived(listOf(1L), true)

        // Exactly what the scanner does to a file whose size changed: `INSERT OR REPLACE`, which in SQL
        // rebuilds the row rather than updating it. If organisation or membership hung off `media`, this
        // is where it would vanish.
        val rewritten = requireNotNull(store.media[1L]).copy(sizeBytes = 4_096L)
        media.upsertAll(listOf(rewritten))

        val state = requireNotNull(store.organizationOf(1L))
        assertEquals("still a favourite", true, state.favorite)
        assertEquals("still archived", true, state.archived)
        assertEquals("still a member of the album", listOf(1L), albumRepository.membersWithin(albumId, listOf(1L)))
        assertEquals("and the rewrite is visible", 4_096L, store.media.getValue(1L).sizeBytes)
    }

    @Test
    fun aFileThatLeftTheDeviceLeavesTheAlbumCountTheFavouriteAndTheBackupRecordBehind() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val albumId = requireNotNull(albumRepository.create("Trip"))
        albumRepository.addMedia(albumId, listOf(1L, 2L))
        organizationRepository.setFavorite(listOf(2L), true)
        queue.withMedia(2L)
        queue.insertMissing(listOf(2L), UploadState.BackedUp.storageKey, 7L)

        // The scan's prune, then the two sweeps that run in the same transaction — the end state of a
        // file deleted outside the app.
        store.media.remove(2L)
        organization.cleanupOrphans()
        albums.cleanupOrphanMemberships()

        assertEquals("the album no longer counts a file that is gone", 1, requireNotNull(albumRepository.observeAlbum(albumId).first()).itemCount)
        assertEquals(
            "and a favourite for a file nobody has cannot inflate the favourites album",
            0,
            organizationRepository.observeCounts().first().of(SystemAlbum.Favorites),
        )
        assertEquals(
            "the backup record for that file stays, because the copy in the channel is still there",
            UploadState.BackedUp.storageKey,
            queue.row(2L).state,
        )
    }

    @Test
    fun oneItemIsInItsFolderAlbumItsTypeAlbumAndFavoritesAtOnce() = runBlocking {
        store.index(FakeMediaRow(1L, type = MediaType.Video))
        organizationRepository.setFavorite(listOf(1L), true)

        val counts = organizationRepository.observeCounts().first()

        assertEquals(1, counts.of(SystemAlbum.Camera))
        assertEquals(1, counts.of(SystemAlbum.Videos))
        assertEquals(1, counts.of(SystemAlbum.Favorites))
        assertEquals(1, counts.of(SystemAlbum.RecentlyAdded))
        assertEquals("it is in no album the user marked it not being in", 0, counts.of(SystemAlbum.Archive))
        assertEquals(0, counts.of(SystemAlbum.Trash))
    }

    @Test
    fun trashingLeavesTheAlbumsListButNotTheMembershipAndBackToBothOnRestore() = runBlocking {
        store.index(FakeMediaRow(1L))
        val albumId = requireNotNull(albumRepository.create("Trip"))
        albumRepository.addMedia(albumId, listOf(1L))

        organizationRepository.moveToTrash(listOf(1L))

        assertEquals(
            "the album reads empty while its one item is in Trash",
            0,
            requireNotNull(albumRepository.observeAlbum(albumId).first()).itemCount,
        )
        assertEquals(emptyList<Long>(), albumRepository.observeContents(albumId, limit = 5).first().map { it.id })
        assertEquals("the user's decision itself is intact", 1, store.members.size)

        organizationRepository.restoreFromTrash(listOf(1L))

        assertEquals(1, requireNotNull(albumRepository.observeAlbum(albumId).first()).itemCount)
        assertEquals(listOf(1L), albumRepository.observeContents(albumId, limit = 5).first().map { it.id })
        assertNull("and Trash has nothing left in it", store.organizationOf(1L)?.trashedAt?.takeIf { it > 0L })
    }

    @Test
    fun anAlbumDeletionReachesNeitherTheIndexNorTheOrganisationNorTheQueue() = runBlocking {
        store.index(FakeMediaRow(1L))
        queue.withMedia(1L)
        queue.insertMissing(listOf(1L), UploadState.Queued.storageKey, 7L)
        val albumId = requireNotNull(albumRepository.create("Trip"))
        albumRepository.addMedia(albumId, listOf(1L))
        organizationRepository.setFavorite(listOf(1L), true)

        assertEquals(true, albumRepository.delete(albumId))

        assertEquals("the file is still indexed", 1, store.media.size)
        assertEquals("still a favourite", true, store.organizationOf(1L)?.favorite)
        assertEquals("still queued", UploadState.Queued.storageKey, queue.row(1L).state)
        assertEquals("only the membership went, by cascade", 0, store.members.size)
    }

    private companion object {
        const val NOW = 1_790_000_000L
    }
}

package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.organization.FakeAlbumDao
import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.organization.AlbumRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * User albums: what an album holds, and the reach of deleting one.
 *
 * The repository runs over the in-memory DAOs, so what is under test is its policy — which ids may become
 * members, what a duplicate add costs, how a count and a cover are derived — rather than SQLite. The
 * dedup rule belongs to the composite primary key and the fake honours it, because "add the same photo
 * twice" has to be measured against the guarantee the database actually gives.
 */
class AlbumRepositoryTest {
    private val store = OrganizationStore()
    private val organization = FakeMediaOrganizationDao(store)
    private val albums = FakeAlbumDao(store)
    private val queue = FakeBackupQueueDao()
    private var clock = 1_000L

    private val repository: AlbumRepository =
        AlbumRepositoryImpl(albums = albums, nowSeconds = { clock })

    @Test
    fun anAlbumIsCreatedWithANameAndTheTimeItWasMade() = runBlocking {
        val id = requireNotNull(repository.create("Summer Trip"))

        val album = repository.observeAlbum(id).first()
        assertEquals("Summer Trip", album?.name)
        assertEquals(0, album?.itemCount)
        assertEquals(1_000L, album?.createdAtSeconds)
        assertNull("an empty album has nothing to show as a cover", album?.coverUri)
    }

    @Test
    fun aNameOfOnlySpacesCreatesNothing() = runBlocking {
        assertNull(repository.create("     "))
        assertNull(repository.create(""))
        assertEquals(emptyList<Long>(), albums.albumIds())
    }

    @Test
    fun aVeryLongNameIsTruncatedRatherThanRefused() = runBlocking {
        val id = requireNotNull(repository.create("t".repeat(400)))

        assertEquals(
            "a name typed out long is still a name the user chose, so it is cut rather than rejected",
            AlbumRepository.MAX_NAME_LENGTH,
            repository.observeAlbum(id).first()?.name?.length,
        )
    }

    @Test
    fun renamingChangesTheNameAndNothingElse() = runBlocking {
        val id = requireNotNull(repository.create("Receipts"))
        store.index(FakeMediaRow(7L))
        repository.addMedia(id, listOf(7L))
        clock = 5_000L

        assertTrue(repository.rename(id, "Paperwork"))
        val album = repository.observeAlbum(id).first()

        assertEquals("Paperwork", album?.name)
        assertEquals("the album was not re-made", 1_000L, album?.createdAtSeconds)
        assertEquals(1, album?.itemCount)
        assertEquals("a name nobody typed renames nothing", false, repository.rename(999L, "Ghost"))
    }

    @Test
    fun blankRenameIsRefused() = runBlocking {
        val id = requireNotNull(repository.create("Trip"))

        assertEquals(false, repository.rename(id, "  "))
        assertEquals("Trip", repository.observeAlbum(id).first()?.name)
    }

    @Test
    fun membersAreAddedOnceAndOnlyFromTheIndex() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val id = requireNotNull(repository.create("Trip"))

        assertEquals(2, repository.addMedia(id, listOf(1L, 2L)))
        assertEquals(
            "the composite key makes a second add the same row, and the count says so",
            0,
            repository.addMedia(id, listOf(1L, 2L)),
        )
        assertEquals(
            "an id the scanner never indexed cannot be a member of anything",
            0,
            repository.addMedia(id, listOf(99L)),
        )
        assertEquals(2, repository.observeAlbum(id).first()?.itemCount)
    }

    @Test
    fun addingToAnAlbumThatDoesNotExistAddsNothing() = runBlocking {
        store.index(FakeMediaRow(1L))

        assertEquals(
            "the foreign key would have thrown instead, so this is the checked version of that safety",
            0,
            repository.addMedia(4242L, listOf(1L)),
        )
    }

    @Test
    fun removingAnItemLeavesTheItemAndItsOtherAlbumsAlone() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val summer = requireNotNull(repository.create("Summer"))
        val trip = requireNotNull(repository.create("Trip"))
        repository.addMedia(summer, listOf(1L, 2L))
        repository.addMedia(trip, listOf(1L))

        assertEquals(1, repository.removeMedia(summer, listOf(1L)))

        assertEquals(listOf(2L), repository.membersWithin(summer, listOf(1L, 2L)))
        assertEquals(listOf(1L), repository.membersWithin(trip, listOf(1L, 2L)))
        assertEquals("the photo itself is still in the library", 2, store.media.size)
        assertEquals(
            "taking it out of one album leaves the other one holding it",
            listOf(trip),
            repository.albumsContaining(1L),
        )
        assertEquals(
            "and the item that was never removed stays filed where it was",
            listOf(summer),
            repository.albumsContaining(2L),
        )
    }

    @Test
    fun deletingAnAlbumTakesItsMembershipAndNothingElse() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val id = requireNotNull(repository.create("Summer"))
        repository.addMedia(id, listOf(1L, 2L))
        organization.setFavorite(1L, true)
        queue.withMedia(1L)
        queue.insertMissing(listOf(1L), UploadState.Queued.storageKey, 7L)

        assertTrue(repository.delete(id))

        assertNull("the album is gone", repository.observeAlbum(id).first())
        assertEquals("and so is its membership", 0, albums.countMembers(id))
        assertEquals("the files stay", 2, store.media.size)
        assertEquals("the organisation stays", true, store.organizationOf(1L)?.favorite)
        assertEquals(
            "and the backup record stays too — deleting a way of looking at photos un-backs up nothing",
            UploadState.Queued.storageKey,
            queue.row(1L).state,
        )
    }

    @Test
    fun theCoverIsTheNewestMemberAndTheCountIgnoresItemsGoneFromTheIndex() = runBlocking {
        store.index(
            FakeMediaRow(1L, dateAddedSeconds = 100L),
            FakeMediaRow(2L, dateAddedSeconds = 300L),
            FakeMediaRow(3L, dateAddedSeconds = 200L),
        )
        val id = requireNotNull(repository.create("Trip"))
        repository.addMedia(id, listOf(1L, 2L, 3L))

        val album = requireNotNull(repository.observeAlbum(id).first())
        assertEquals(3, album.itemCount)
        assertEquals("content://media/external/images/media/2", album.coverUri)

        store.media.remove(2L)
        val after = requireNotNull(repository.observeAlbum(id).first())

        assertEquals("a member the device no longer has is not counted", 2, after.itemCount)
        assertEquals(
            "and the cover moves to the newest one that is still here",
            "content://media/external/images/media/3",
            after.coverUri,
        )
    }

    @Test
    fun anItemInTrashLeavesEveryAlbumViewUntilItIsRestored() = runBlocking {
        store.index(FakeMediaRow(1L), FakeMediaRow(2L))
        val id = requireNotNull(repository.create("Trip"))
        repository.addMedia(id, listOf(1L, 2L))
        organization.setTrashedAt(1L, 5L)

        val contents = repository.observeContents(id, limit = 10).first()

        assertEquals(listOf(2L), contents.map { it.id })
        assertEquals(
            "both membership rows are still there, so restoring brings the item back",
            2,
            store.members.size,
        )
    }

    @Test
    fun albumsAreListedNewestFirstAndContentsAreOrderedByArrival() = runBlocking {
        store.index(
            FakeMediaRow(1L, dateAddedSeconds = 10L),
            FakeMediaRow(2L, dateAddedSeconds = 20L),
            FakeMediaRow(3L, dateAddedSeconds = 30L),
        )
        val older = requireNotNull(repository.create("Older"))
        clock = 2_000L
        val newer = requireNotNull(repository.create("Newer"))
        repository.addMedia(older, listOf(1L, 2L, 3L))

        assertEquals(listOf(newer, older), repository.observeAlbums().first().map { it.id })
        assertEquals(
            "newest member first, which is the timeline's own rule rather than a new one",
            listOf(3L, 2L, 1L),
            repository.observeContents(older, limit = 10).first().map { it.id },
        )
    }

    @Test
    fun anEmptySelectionTouchesNothing() = runBlocking {
        store.index(FakeMediaRow(1L))
        val id = requireNotNull(repository.create("Trip"))

        assertEquals(0, repository.addMedia(id, emptyList()))
        assertEquals(0, repository.removeMedia(id, emptyList()))
        assertEquals(emptyList<Long>(), repository.membersWithin(id, emptyList()))
        assertEquals(0, repository.observeAlbum(id).first()?.itemCount)
    }
}

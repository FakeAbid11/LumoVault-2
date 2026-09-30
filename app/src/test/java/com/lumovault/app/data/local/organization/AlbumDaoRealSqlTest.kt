package com.lumovault.app.data.local.organization

import android.app.Application
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [AlbumDao.observeAlbums] read out of a real SQLite database, because the query's own header makes
 * claims only SQLite can settle.
 *
 * The fakes in this package answer from maps: they agree with each other and with the Kotlin the
 * tests beside them run, and none of that can prove the SQL right. The `LEFT JOIN` + `COALESCE` the
 * Dao's comment calls load-bearing — a member with no `media_organization` row has to count, because
 * `NULL = 0` is not true — is exactly the shape a scalar subquery gets wrong while looking entirely
 * reasonable, and the same is true of the count's Trash filter, the cover's newest-first pick, and
 * the list's ordering. Each is asserted here through the query the Albums screen subscribes to.
 *
 * A plain [Application] for the same reason as [com.lumovault.app.data.local.MigrationUpgradeTest]:
 * the real application's `onCreate` builds a dependency graph, a notification channel and a
 * WorkManager schedule, and none of it is what this test is about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AlbumDaoRealSqlTest {
    private lateinit var database: LumoVaultDatabase

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LumoVaultDatabase::class.java,
        ).build()
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aMemberNoOrganisationRowHasEverTouchedStillCountsAndCovers() = runBlocking {
        database.mediaDao().upsertAll(
            listOf(FakeMediaRow(id = 101, dateAddedSeconds = 1_000).toEntity()),
        )
        val albumId = database.albumDao().insert(AlbumEntity(name = "Trips", createdAt = 500))
        database.albumDao().addMembers(albumId, listOf(101), now = 1)

        val row = database.albumDao().observeAlbums().first().single()

        // o.trashed_at is NULL for this member, and NULL = 0 is not true: the correlated-subquery
        // version of this filter counts zero here, which is the bug the COALESCE exists to prevent.
        assertEquals(1, row.itemCount)
        assertEquals("content://media/external/images/media/101", row.coverUri)
    }

    @Test
    fun aTrashedMemberIsNeitherCountedNorUsedAsTheCover() = runBlocking {
        database.mediaDao().upsertAll(
            listOf(
                FakeMediaRow(id = 201, dateAddedSeconds = 1_000).toEntity(),
                FakeMediaRow(id = 202, dateAddedSeconds = 2_000).toEntity(),
            ),
        )
        val albumId = database.albumDao().insert(AlbumEntity(name = "Trips", createdAt = 500))
        database.albumDao().addMembers(albumId, listOf(201, 202), now = 1)
        database.mediaOrganizationDao().setTrashedAt(202, 42)

        val row = database.albumDao().observeAlbums().first().single()

        // 202 is the newer upload, and Trash outranks recency: both the count and the cover skip it.
        assertEquals(1, row.itemCount)
        assertEquals("content://media/external/images/media/201", row.coverUri)
    }

    @Test
    fun onATieForNewestTheHigherMediaStoreIdIsTheCover() = runBlocking {
        database.mediaDao().upsertAll(
            listOf(
                FakeMediaRow(id = 301, dateAddedSeconds = 1_000).toEntity(),
                FakeMediaRow(id = 302, dateAddedSeconds = 1_000).toEntity(),
            ),
        )
        val albumId = database.albumDao().insert(AlbumEntity(name = "Trips", createdAt = 500))
        database.albumDao().addMembers(albumId, listOf(301, 302), now = 1)

        val row = database.albumDao().observeAlbums().first().single()

        assertEquals(2, row.itemCount)
        assertEquals("content://media/external/images/media/302", row.coverUri)
    }

    @Test
    fun anAlbumWithNoMembersIsShownEmpty() = runBlocking {
        database.albumDao().insert(AlbumEntity(name = "Emptied", createdAt = 500))

        val row = database.albumDao().observeAlbums().first().single()

        // A null cover is what draws the placeholder; a guessed one would be a wrong thumbnail.
        assertEquals(0, row.itemCount)
        assertNull(row.coverUri)
    }

    @Test
    fun theListIsOrderedNewestFirstThenByDescendingId() = runBlocking {
        database.albumDao().insert(AlbumEntity(name = "First", createdAt = 10))
        database.albumDao().insert(AlbumEntity(name = "Second", createdAt = 20))
        database.albumDao().insert(AlbumEntity(name = "Third", createdAt = 20))

        val names = database.albumDao().observeAlbums().first().map { row -> row.name }

        // Same creation time means the higher id — the later insert — comes first.
        assertEquals(listOf("Third", "Second", "First"), names)
    }
}

package com.lumovault.app.data.local.cloud

import android.app.Application
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.repository.CloudIndexRepositoryImpl
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Select all over the cloud index, beside the window it must mirror, out of a real SQLite database.
 *
 * `CloudMediaDao.allIds` claims to be `observeWindow` without the `LIMIT` — same table, same rows,
 * ids only — and `itemsFor` claims to answer any selection a strip can hold, including ids the grid
 * never loaded and an id list past the query ceiling. The fake repositories agree with themselves,
 * which proves nothing about the SQL, so each pairing is asserted here: a drift would surface on a
 * phone as a count the Cloud strip prints that does not match the downloads it starts.
 *
 * A plain [Application] for the same reason as `MigrationUpgradeTest`: the real application's
 * `onCreate` builds a dependency graph, a notification channel and a WorkManager schedule, and none
 * of it is what this test is about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CloudSelectAllRealSqlTest {
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

    private fun row(messageId: Long, dateSeconds: Long = messageId * 1_000) = CloudMediaEntity(
        messageId = messageId,
        chatId = CHAT,
        mediaType = MediaType.Photo.storageKey,
        dateSeconds = dateSeconds,
    )

    @Test
    fun selectAllIsTheWindowWithoutItsLimit() = runBlocking<Unit> {
        database.cloudMediaDao().upsertAll((1L..7L).map { id -> row(id) })
        val repository = repository()

        // The full window and the unwindowed id read are one set: whatever the grid would draw
        // across every page is exactly what select all selects.
        val fullWindow = database.cloudMediaDao().observeWindow(100).first().map { it.messageId }
        assertEquals(fullWindow.toSet(), repository.allIds().toSet())
        assertEquals((1L..7L).toSet(), repository.allIds().toSet())

        // And a *short* window is a strict subset — the state the grid is actually in when the
        // strip asks, three rows loaded over a collection of seven.
        val window = database.cloudMediaDao().observeWindow(3).first().map { it.messageId }
        assertEquals(setOf(7L, 6L, 5L), window.toSet())
        assertTrue("select all must reach past the loaded window", repository.allIds().size > window.size)
        assertEquals(repository.allIds().toSet(), window.toSet() + repository.allIds())
    }

    @Test
    fun actionRoutingReadsExactlyTheIdsTheSelectionNamed() = runBlocking<Unit> {
        database.cloudMediaDao().upsertAll((1L..7L).map { id -> row(id) })
        val repository = repository()

        // Ids the three-row window never loaded: a selection assembled by select-all reaches here,
        // and the records it gets back must be those ids and nothing else.
        val routed = repository.itemsFor(listOf(7L, 2L))
        assertEquals(setOf(7L, 2L), routed.map { it.messageId }.toSet())

        // An id the index no longer holds is skipped rather than invented.
        assertEquals(setOf(2L), repository.itemsFor(listOf(2L, 99L)).map { it.messageId }.toSet())

        // Nothing selected is not a question.
        assertEquals(emptyList<CloudMedia>(), repository.itemsFor(emptyList()))
    }

    @Test
    fun anIdListPastTheQueryCeilingStillAnswersEveryRow() = runBlocking<Unit> {
        // One more row than MAX_IDS_PER_QUERY: a select-all over a library this size reaches the
        // chunked read, and a chunk that lost its reassembly would silently shrink the selection.
        val ids = (1L..(MAX_TEST_ROWS)).toList()
        database.cloudMediaDao().upsertAll(ids.map { id -> row(id) })
        val repository = repository()

        assertEquals(ids.toSet(), repository.allIds().toSet())

        val routed = repository.itemsFor(ids)
        assertEquals(ids.size, routed.size)
        assertEquals(ids.toSet(), routed.map { it.messageId }.toSet())
    }

    private fun repository() = CloudIndexRepositoryImpl(
        database = database,
        media = database.cloudMediaDao(),
        channel = database.cloudChannelDao(),
    )

    private companion object {
        const val CHAT = 7_777L

        /** One past `MAX_IDS_PER_QUERY` (400), so the id list is guaranteed to be chunked. */
        const val MAX_TEST_ROWS = 401L
    }
}

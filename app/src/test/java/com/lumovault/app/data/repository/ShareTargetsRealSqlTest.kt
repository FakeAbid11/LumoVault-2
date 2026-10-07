package com.lumovault.app.data.repository

import android.app.Application
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.mediastore.MediaIndexScan
import com.lumovault.app.data.local.mediastore.MediaIndexSource
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.domain.model.ShareableMedia
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What a share sends, beside what the grid drew, out of a real SQLite database.
 *
 * `MediaRepository.shareablesFor` is the row every screen's share button ends on: content URI plus
 * MIME type, read for the *whole* selection — select-all can name rows the grid never loaded — and
 * with ids the index no longer holds skipped rather than invented. The projection itself has no
 * room to be clever, which is exactly why it is asserted against the window's own rows here: a
 * share that disagreed with the grid would hand another app a file the user was not looking at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShareTargetsRealSqlTest {
    private lateinit var database: LumoVaultDatabase
    private lateinit var repository: MediaRepositoryImpl

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LumoVaultDatabase::class.java,
        ).build()
        repository = MediaRepositoryImpl(
            dao = database.mediaDao(),
            // Never called: this test shares, it does not scan, and the source is the seam sync
            // would use if it did.
            source = MediaIndexSource { MediaIndexScan.EmptyLibrary },
            organization = database.mediaOrganizationDao(),
            albums = database.albumDao(),
            metadata = database.mediaMetadataDao(),
            inTransaction = { block -> block() },
            stampScan = {},
        )
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun aShareOfTheWindowSendsExactlyTheRowsTheWindowDrew() = runBlocking<Unit> {
        database.mediaDao().upsertAll((101L..105L).map { id -> FakeMediaRow(id = id).toEntity() })

        val window = repository.observeWindow(2).first()
        val shared = repository.shareablesFor(window.map { it.id })

        assertEquals(
            window.map { media -> ShareableMedia(uri = media.contentUri, mimeType = media.mimeType) }.toSet(),
            shared.toSet(),
        )
        // The window is newest-first; the two newest rows are 105 and 104.
        assertEquals(setOf(105L, 104L), window.map { it.id }.toSet())
    }

    @Test
    fun aSelectionPastTheWindowAnswersItsOwnRowsToo() = runBlocking<Unit> {
        database.mediaDao().upsertAll((101L..105L).map { id -> FakeMediaRow(id = id).toEntity() })

        // Select all's ids — including rows the two-row window never held — and one id that is not
        // in the index at all.
        val shared = repository.shareablesFor(listOf(101L, 103L, 105L, 999L))

        assertEquals(
            setOf("content://media/external/images/media/101", "content://media/external/images/media/103",
                "content://media/external/images/media/105"),
            shared.map { it.uri }.toSet(),
        )
        assertEquals(listOf("image/jpeg"), shared.map { it.mimeType }.distinct())
    }

    @Test
    fun anEmptySelectionSharesNothingRatherThanEverything() = runBlocking<Unit> {
        database.mediaDao().upsertAll(listOf(FakeMediaRow(id = 101).toEntity()))

        assertEquals(emptyList<ShareableMedia>(), repository.shareablesFor(emptyList()))
        assertEquals(emptyList<ShareableMedia>(), repository.shareablesFor(listOf(999L)))
    }
}

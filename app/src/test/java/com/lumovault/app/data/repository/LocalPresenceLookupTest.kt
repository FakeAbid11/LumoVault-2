package com.lumovault.app.data.repository

import android.app.Application
import androidx.room.Room
import com.lumovault.app.data.local.LumoVaultDatabase
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.CloudDateSource
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.ShareableMedia
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
 * The three answers [LocalPresenceLookup] gives are one lookup with three faces, asserted over real
 * SQLite because each face is a join or a projection whose SQL is the whole claim:
 *
 *  - the badge: *is it on the device?* (`backedUp`)
 *  - the viewer: *which row is it?* (`localMediaIds` / `localMediaFor`)
 *  - the share sheet: *what travels?* (`rowsForMedia` over those ids)
 *
 * If they were computed by three different matchers, the Cloud screen could tick a cell whose tap
 * then opened nothing, or a share could skip a file the badge calls local. They are derived from
 * one core, and the fixtures below pin the two matching rules — manifest hash first, file name *and*
 * size second — plus the honest absence of a third.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalPresenceLookupTest {
    private lateinit var database: LumoVaultDatabase
    private lateinit var lookup: LocalPresenceLookup

    @Before
    fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            LumoVaultDatabase::class.java,
        ).build()
        lookup = LocalPresenceLookup(media = database.mediaDao(), queue = database.backupQueueDao())
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    private fun cloudItem(
        messageId: Long,
        contentHash: String = "",
        fileName: String = "",
        sizeBytes: Long = 0,
    ) = CloudMedia(
        messageId = messageId,
        chatId = CHAT,
        type = MediaType.Photo,
        mimeType = "",
        fileName = fileName,
        sizeBytes = sizeBytes,
        dateSeconds = messageId,
        dateSource = CloudDateSource.TelegramMessage,
        width = 0,
        height = 0,
        durationSeconds = null,
        remoteFileId = "",
        previewRemoteFileId = "",
        caption = "",
        contentHash = contentHash,
    )

    @Test
    fun theBadgeTheViewerAndTheShareAgreeOnWhichItemsAreOnTheDevice() = runBlocking<Unit> {
        seedDevice()

        // 1: manifest hash, the exact signal. 2: file name and size, the fallback every pre-manifest
        // backup relies on. 3: neither — cloud-only, and absent from every answer.
        val items = listOf(
            cloudItem(messageId = 1, contentHash = HASH),
            cloudItem(messageId = 2, fileName = "IMG_102.jpg", sizeBytes = 1_024),
            cloudItem(messageId = 3),
        )

        val matched = lookup.localMediaIds(items)
        assertEquals(mapOf(1L to 101L, 2L to 102L), matched)
        assertEquals("the badge is the same lookup, keys only", setOf(1L, 2L), lookup.backedUp(items))

        // The viewer's list, in the items' order, cloud-only skipped — and the row each mapped to.
        val rows = lookup.localMediaFor(items)
        assertEquals(listOf(101L, 102L), rows.map { it.id })
        assertEquals("content://media/external/images/media/101", rows.first().contentUri)
        assertEquals("image/jpeg", rows.first().mimeType)
    }

    @Test
    fun twoMessagesHoldingTheSameBytesReachOneRowAndTheShareSendsItOnce() = runBlocking<Unit> {
        seedDevice()

        // Two phones backing up the same file, or a retried send: two messages, one local row.
        // The share sheet dedupes on the view-model side; the row answer must not invent a second.
        val items = listOf(
            cloudItem(messageId = 1, contentHash = HASH),
            cloudItem(messageId = 4, contentHash = HASH),
        )

        assertEquals(mapOf(1L to 101L, 4L to 101L), lookup.localMediaIds(items))
        assertEquals(listOf(101L), lookup.localMediaFor(items).map { it.id })
    }

    @Test
    fun aBackupRecordWhoseFileLeftTheDeviceIsNotOnTheDevice() = runBlocking<Unit> {
        seedDevice()
        // The queue row survives the file leaving — PRD section 72's cloud-only state — so without
        // the join into `media` this would tick a cell for a file the phone no longer holds.
        database.mediaDao().deleteByIds(listOf(101L))

        val items = listOf(cloudItem(messageId = 1, contentHash = HASH))
        assertTrue(lookup.localMediaIds(items).isEmpty())
        assertTrue(lookup.backedUp(items).isEmpty())
        assertTrue(lookup.localMediaFor(items).isEmpty())
    }

    @Test
    fun rowsForMediaAnswersTheAskedIdsInOrderAndSkipsNothingThatExists() = runBlocking<Unit> {
        seedDevice()

        val rows = lookup.rowsForMedia(listOf(102L, 101L, 102L))
        assertEquals(listOf(102L, 101L), rows.map { it.id })

        // An id the index does not hold is skipped, not invented — a share of a file that has left
        // the device shares nothing, never a stale path.
        val shareables = rows.map { row -> ShareableMedia(uri = row.contentUri, mimeType = row.mimeType) }
        assertEquals(2, shareables.size)
        assertTrue(shareables.none { it.uri.isBlank() })
    }

    /** Two media rows, a backup record hashing one of them, and one file nobody recorded. */
    private suspend fun seedDevice() {
        database.mediaDao().upsertAll(
            listOf(
                FakeMediaRow(id = 101).toEntity(),
                FakeMediaRow(id = 102).toEntity(),
            ),
        )
        database.backupQueueDao().recordIdentity(
            id = 101,
            notBackedUpState = UploadState.NotBackedUp.storageKey,
            hash = HASH,
            sizeBytes = 1_024,
            modifiedSeconds = 10,
            hashedAt = 1,
        )
    }

    private companion object {
        const val CHAT = 7_777L
        const val HASH = "d0e5f30a" + "deadbeefcafe0123456789abcdef"
    }
}

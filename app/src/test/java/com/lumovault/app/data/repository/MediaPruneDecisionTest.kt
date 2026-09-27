package com.lumovault.app.data.repository

import com.lumovault.app.data.local.media.MediaEntity
import com.lumovault.app.data.local.metadata.MediaMetadataEntity
import com.lumovault.app.data.local.mediastore.MediaIndexScan
import com.lumovault.app.data.local.mediastore.MediaIndexSource
import com.lumovault.app.data.local.mediastore.prunesIndex
import com.lumovault.app.data.local.organization.AlbumKey
import com.lumovault.app.data.local.organization.FakeAlbumDao
import com.lumovault.app.data.local.organization.FakeMediaDao
import com.lumovault.app.data.local.organization.FakeMediaOrganizationDao
import com.lumovault.app.data.local.organization.FakeMediaRow
import com.lumovault.app.data.local.organization.MediaOrganizationEntity
import com.lumovault.app.data.local.organization.OrganizationStore
import com.lumovault.app.data.local.metadata.FakeMediaMetadataDao
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a scan is allowed to remove, asserted against the real orphan semantics rather than a count of calls.
 *
 * `sync` is destructive in a way nothing else in the data layer is. It prunes every media row the scan did not
 * tag, then runs three sweeps written as `NOT IN (SELECT media_store_id FROM media)` — which, over a `media`
 * table that was just emptied, is true of every row in all three. The favourites, archive marks, trash
 * timestamps, album memberships and GPS positions in those tables cannot be rebuilt by the next scan, because
 * MediaStore never held any of them. So the question this file answers is not "how many rows went away" but
 * "did a provider that failed to answer cost the user their library".
 *
 * The Phase 7 fakes are what make the assertions worth anything: `pruneBefore` really is
 * `last_seen_scan_id < scanId`, `upsertAll` really does rebuild the row, and each sweep derives its answer from
 * what `media` currently holds rather than from a flag this test could simply set.
 */
class MediaPruneDecisionTest {
    private val store = OrganizationStore()
    private val dao = FakeMediaDao(store)
    private val organization = FakeMediaOrganizationDao(store)
    private val albums = FakeAlbumDao(store)
    private val metadata = FakeMediaMetadataDao(store)

    /** Counts the stamps, so "the settings row was left alone" is an observation rather than an assumption. */
    private var stamps = 0

    private fun repository(source: MediaIndexSource) = MediaRepositoryImpl(
        dao = dao,
        source = source,
        organization = organization,
        albums = albums,
        metadata = metadata,
        inTransaction = { block -> block() },
        stampScan = { stamps++ },
    )

    private fun indexLibraryOf(vararg ids: Long) {
        ids.forEach { store.media[it] = FakeMediaRow(it).toEntity() }
        store.bump()
    }

    /** The three kinds of thing a user decided or the app paid to learn, on the same ids. */
    private fun seedDecisionsOn(vararg ids: Long) {
        ids.forEach { id ->
            store.organization[id] = MediaOrganizationEntity(mediaStoreId = id, favorite = true)
            store.members.add(AlbumKey(albumId = ALBUM_ID, mediaStoreId = id))
            store.metadata[id] = MediaMetadataEntity(mediaStoreId = id, latitude = 52.0, longitude = 0.1)
        }
    }

    private fun assertDecisionsIntact(vararg ids: Long) {
        ids.forEach { id ->
            assertTrue("photo $id is still indexed", store.media.containsKey(id))
            assertTrue("photo $id is still a favourite", store.organization[id]?.favorite == true)
            assertTrue("photo $id is still in the album", AlbumKey(ALBUM_ID, id) in store.members)
            assertTrue("photo $id still has its position", store.metadata.containsKey(id))
        }
    }

    @Test
    fun aScanThatCouldNotConcludeRemovesNothingAtAll() = runBlocking {
        indexLibraryOf(1L, 2L, 3L)
        seedDecisionsOn(1L, 2L, 3L)

        val result = repository(answering { MediaIndexScan.CouldNotConclude }).sync()

        assertFalse("the pass says plainly that it did not reconcile", result.reconciled)
        assertEquals(0, result.indexed)
        assertEquals("and no removal is reported, because none was earned", 0, result.removed)
        assertDecisionsIntact(1L, 2L, 3L)
        assertEquals("the settings row is untouched, so diagnostics cannot name a scan that never ran", 0, stamps)
    }

    @Test
    fun anEmptyLibraryIsStillAnAnswerAndTheIndexFollowsIt() = runBlocking {
        indexLibraryOf(1L, 2L, 3L)
        seedDecisionsOn(1L, 2L, 3L)

        val result = repository(answering { MediaIndexScan.EmptyLibrary }).sync()

        assertTrue(
            "a cursor that opened and held nothing means the photos really are gone; an index that kept them " +
                "would draw an empty library and offer to back it up",
            result.reconciled,
        )
        assertEquals(0, store.media.size)
        assertEquals(0, store.organization.size)
        assertEquals("the membership goes by the same rule every other sweep uses", 0, store.members.size)
        assertEquals(0, store.metadata.size)
        assertEquals(1, stamps)
    }

    @Test
    fun aScanThatSawMostOfTheLibraryPrunesOnlyWhatIsGone() = runBlocking {
        indexLibraryOf(1L, 2L, 3L)
        seedDecisionsOn(1L, 2L, 3L)

        // Exactly what the real scanner does with the id it is handed: everything still on the device comes
        // back tagged, and what it omits is all the prune is entitled to remove.
        val result = repository(
            answering { scanId ->
                MediaIndexScan.Found(
                    listOf(1L, 2L).map { id -> store.media.getValue(id).copy(lastSeenScanId = scanId) },
                )
            },
        ).sync()

        assertTrue(result.reconciled)
        assertEquals(2, result.indexed)
        assertEquals(1, result.removed)
        assertDecisionsIntact(1L, 2L)
        assertFalse("photo 3 left the device, so its row goes", store.media.containsKey(3L))
        assertFalse("and so does the position that was only ever keyed to it", store.metadata.containsKey(3L))
        assertFalse(AlbumKey(ALBUM_ID, 3L) in store.members)
    }

    @Test
    fun anInconclusivePassNeedsNoRecoveryStepAfterIt() = runBlocking {
        indexLibraryOf(1L, 2L)
        seedDecisionsOn(1L, 2L)

        assertFalse(repository(answering { MediaIndexScan.CouldNotConclude }).sync().reconciled)

        // The next pass must not have to know the first one happened: nothing was stamped, so an ordinary scan
        // of the same library is a complete recovery rather than a partial one that left a stale tag behind.
        val recovering = repository(
            answering { scanId ->
                MediaIndexScan.Found(store.media.values.map { row -> row.copy(lastSeenScanId = scanId) })
            },
        ).sync()

        assertTrue(recovering.reconciled)
        assertEquals(2, recovering.indexed)
        assertEquals("nothing went missing in between, so nothing needs to be pruned away", 0, recovering.removed)
        assertDecisionsIntact(1L, 2L)
        assertEquals("and the one stamp is the recovery's, never the refused pass's", 1, stamps)
    }

    @Test
    fun onlyAnInconclusiveScanRefusesToRemoveRows() {
        // The two cases that look identical as a list and are not identical at the prune.
        assertTrue(MediaIndexScan.Found(listOf<MediaEntity>()).prunesIndex())
        assertTrue(MediaIndexScan.EmptyLibrary.prunesIndex())
        assertFalse(MediaIndexScan.CouldNotConclude.prunesIndex())
    }

    private companion object {
        const val ALBUM_ID = 7L

        /**
         * A source that answers whatever the test says, however it is asked.
         *
         * Written as an object rather than a lambda so nothing here depends on SAM conversion of a `suspend`
         * member of a `fun interface` — a shape worth avoiding when the alternative is one line longer and a
         * compile error is a whole build cycle away.
         */
        fun answering(
            scanIdToScan: suspend (Long) -> MediaIndexScan,
        ): MediaIndexSource = object : MediaIndexSource {
            override suspend fun scan(scanId: Long): MediaIndexScan = scanIdToScan(scanId)
        }
    }
}

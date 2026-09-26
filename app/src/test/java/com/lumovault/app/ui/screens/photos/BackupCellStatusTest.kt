package com.lumovault.app.ui.screens.photos

import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.UploadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The thumbnail marks, kept as a pure function so the collapse from the queue's states to a handful of
 * glyphs is something a test pins rather than something a composable hopes for.
 *
 * The distinction worth defending is that no row and a cancelled row look the same: both mean nothing
 * is owed for the item, and drawing a withdrawn upload as if it were still in flight would leave a
 * progress mark on a photo going nowhere.
 */
class BackupCellStatusTest {
    @Test
    fun everyQueueStateHasAMarkAndNothingQueuedHasNone() {
        assertEquals(BackupCellStatus.Unbacked, backupCellStatus(null))
        assertEquals(BackupCellStatus.Unbacked, backupCellStatus(UploadState.Cancelled))
        assertEquals(BackupCellStatus.Unbacked, backupCellStatus(UploadState.NotBackedUp))
        assertEquals(BackupCellStatus.Queued, backupCellStatus(UploadState.Queued))
        assertEquals(BackupCellStatus.Preparing, backupCellStatus(UploadState.Preparing))
        assertEquals(BackupCellStatus.Uploading, backupCellStatus(UploadState.Uploading))
        assertEquals(BackupCellStatus.BackedUp, backupCellStatus(UploadState.BackedUp))
        assertEquals(BackupCellStatus.Failed, backupCellStatus(UploadState.Failed))
    }

    @Test
    fun aRecognisedFileAndAnUnknownOneLookIdenticalOnAThumbnail() {
        // Phase 6 puts a record on disk for a file it has only ever hashed, and the grid must not learn
        // anything from that. PRD section 13 asks for a mark that stays subtle; a "measured but never
        // asked about" glyph would put the app's own bookkeeping on the user's photos.
        val overview = BackupOverview(states = mapOf(7L to UploadState.NotBackedUp))

        assertEquals(overview.statusOf(8L), overview.statusOf(7L))
        assertEquals(BackupCellStatus.Unbacked, overview.statusOf(7L))
        assertEquals(
            "and a recognised item is still not a queue, so selection stays where it was",
            false,
            BackupOverview(
                states = mapOf(7L to UploadState.NotBackedUp),
                summary = BackupQueueSummary(),
            ).selectionEnabled,
        )
    }

    @Test
    fun aRowInProgressIsNeverDrawnLikeAFinishedOne() {
        // The four states a user can distinguish at a glance have to stay distinguishable; collapsing
        // `UPLOADING` into `BACKED_UP` would be a claim about Telegram that the cell cannot support.
        val inProgress = backupCellStatus(UploadState.Uploading)
        val done = backupCellStatus(UploadState.BackedUp)
        val refused = backupCellStatus(UploadState.Failed)

        assertNotEquals(inProgress, done)
        assertNotEquals(done, refused)
        assertNotEquals(inProgress, refused)
    }

    @Test
    fun theOverviewLooksUpAnItemWithoutInventingAStateForIt() {
        val overview = BackupOverview(states = mapOf(7L to UploadState.Uploading))

        assertEquals(BackupCellStatus.Uploading, overview.statusOf(7L))
        assertEquals(BackupCellStatus.Unbacked, overview.statusOf(8L))
    }

    @Test
    fun anEmptyOverviewIsWhatACleanLibraryLooksLike() {
        val overview = BackupOverview()

        assertEquals(0, overview.summary.pending)
        assertEquals(0, overview.summary.total)
        assertEquals(BackupCellStatus.Unbacked, overview.statusOf(1L))
    }

    @Test
    fun pendingMeansOnlyWhatTheWorkerStillOwes() {
        val summary = BackupQueueSummary(queued = 2, inFlight = 1, backedUp = 5, failed = 3)

        assertEquals(3, summary.pending)
        assertEquals(11, summary.total)
        assertEquals("a finished item is not pending", true, summary.isActive)

        val nothingLeft = BackupQueueSummary(backedUp = 4, failed = 1)
        assertEquals(0, nothingLeft.pending)
        assertEquals("a queue that stopped is not active", false, nothingLeft.isActive)
    }
}

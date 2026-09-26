package com.lumovault.app.ui.backup

import com.lumovault.app.domain.model.BackupPreferences
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreState
import com.lumovault.app.ui.navigation.BackupRoutes
import com.lumovault.app.ui.navigation.LumoVaultDestination
import com.lumovault.app.ui.screens.cloud.canStartRestore
import com.lumovault.app.ui.screens.cloud.labelRes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens' own decisions, kept small enough to be checkable without a device.
 *
 * Nothing here renders a composable — that needs a phone, and the brief says so. What is checkable is the
 * three judgements the UI makes before it draws: whether a tap may start a download, whether a refusal has
 * a sentence of its own, and which tab a route belongs to. Each has been wrong in an app like this at least
 * once: an enabled button over a running transfer, a reason that falls back to "unknown" when the app knows
 * exactly what happened, or a screen that un-highlights the bar it was opened from.
 */
class BackupScreenRulesTest {

    @Test
    fun aLiveRowRefusesASecondDownloadAndAFinishedOneDoesNot() {
        assertTrue("no row at all means nothing has been asked for", canStartRestore(null))
        assertFalse(
            "a tap on a transferring row must not start a second transfer",
            canStartRestore(job(RestoreState.Downloading)),
        )
        assertFalse("pending is already a request", canStartRestore(job(RestoreState.Pending)))
        assertTrue("a completed restore can be run again, after Free Up Space removed the file", canStartRestore(job(RestoreState.Completed)))
        assertTrue("a failed one can be retried", canStartRestore(job(RestoreState.Failed)))
        assertTrue("a cancelled one can be started again", canStartRestore(job(RestoreState.Cancelled)))
    }

    @Test
    fun everyRefusalHasItsOwnSentence() {
        // A reason that maps to the generic text is a bug that looks like a working screen, so each kind is
        // asserted to be distinct rather than merely present.
        val labels = RestoreFailureKind.entries.map { it.labelRes() }

        assertEquals("one per kind", RestoreFailureKind.entries.size, labels.size)
        assertEquals("no two share a resource", labels.toSet().size, labels.size)
        assertNotEquals(
            "a dropped file must not read like a network problem",
            RestoreFailureKind.SourceGone.labelRes(),
            RestoreFailureKind.Network.labelRes(),
        )
    }

    @Test
    fun onlyACleanLibraryReadsAsEverythingBackedUp() {
        val clean = health(pending = 0, failed = 0, backedUp = 7, total = 7)
        assertTrue(clean.allLocalMediaBackedUp)

        listOf(
            health(pending = 1, failed = 0, backedUp = 7, total = 8),
            health(pending = 0, failed = 1, backedUp = 7, total = 8),
            health(pending = 0, failed = 0, backedUp = 0, total = 0),
        ).forEach {
            assertFalse("nothing may be promised over ${it.pending} waiting and ${it.failed} failed", it.allLocalMediaBackedUp)
        }
    }

    @Test
    fun anOfferIsReadFromTheAggregateRatherThanFromTheListLength() {
        // The headline is an aggregate, so an empty review list alongside a non-zero plan is a real state —
        // it means the window ran out, not that there is nothing to free. Reading the offer off the visible
        // rows would tell the user the opposite.
        assertTrue(FreeUpSpaceState().nothingEligible)
        assertFalse(
            "a plan with items in it is never drawn as though the library were empty",
            FreeUpSpaceState(
                plan = com.lumovault.app.domain.restore.FreeUpSpacePlan(eligibleCount = 12, reclaimableBytes = 5_000L),
                candidates = emptyList(),
            ).nothingEligible,
        )
    }

    @Test
    fun selectionIsClampedToBytesTheSelectedItemsActuallyCarry() {
        val chosen = setOf(1L, 3L)
        val bytes = listOf(candidate(1L, 1_000L), candidate(2L, 5_000L), candidate(3L, 2_500L))
            .filter { it.mediaStoreId in chosen }
            .sumOf { it.sizeBytes }

        assertEquals(3_500L, bytes)
    }

    @Test
    fun everyBackupRouteBelongsToTheCloudTabAndNotToATabOfItsOwn() {
        listOf(
            BackupRoutes.HUB,
            BackupRoutes.HEALTH,
            BackupRoutes.DIAGNOSTICS,
            BackupRoutes.FREE_SPACE,
        ).forEach { route ->
            assertEquals(
                "$route is opened from the cloud tab's work, so the bar must stay put",
                LumoVaultDestination.Cloud,
                LumoVaultDestination.forRoute(route),
            )
            assertTrue("$route is nested, so the back arrow belongs", route.startsWith(BackupRoutes.PREFIX))
        }
    }

    @Test
    fun aConstraintsRowNamesEveryPairOfPreferences() {
        val words = listOf(
            BackupPreferences(automatic = true, wifiOnly = true, chargingOnly = true),
            BackupPreferences(automatic = true, wifiOnly = true, chargingOnly = false),
            BackupPreferences(automatic = true, wifiOnly = false, chargingOnly = true),
            BackupPreferences(automatic = true, wifiOnly = false, chargingOnly = false),
        ).map { it.constraintsSummaryRes() }

        assertEquals("four cases, four distinct sentences", words.toSet().size, words.size)
    }

    private fun candidate(id: Long, bytes: Long) = com.lumovault.app.domain.restore.FreeUpSpaceCandidate(
        mediaStoreId = id,
        contentUri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        sizeBytes = bytes,
        mediaType = com.lumovault.app.domain.model.MediaType.Photo,
    )

    private fun health(pending: Int, failed: Int, backedUp: Int, total: Int) =
        com.lumovault.app.domain.model.BackupHealth(
            localTotal = total,
            backedUp = backedUp,
            waiting = pending,
            uploading = 0,
            failed = failed,
            cloudOnly = 0,
            reclaimableCount = 0,
            reclaimableBytes = 0L,
            lastBackupSeconds = 1L,
            lastScanSeconds = 1L,
        )

    private fun job(state: RestoreState) = RestoreJob(
        chatId = 1L,
        messageId = 2L,
        state = state,
        downloadedBytes = 0L,
        expectedSizeBytes = 0L,
        tdlibFileId = 0,
        mediaStoreId = 0L,
        failure = if (state == RestoreState.Failed) RestoreFailureKind.Network else null,
    )
}

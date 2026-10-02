package com.lumovault.app.ui.screens.cloud

import com.lumovault.app.domain.model.CloudDateSource
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.telegram.CloudInitState
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Cloud tab's state rules, decided away from the composable.
 *
 * The rule with a history here is the file's own invariant: an index that already holds rows is never
 * replaced by a spinner. The preparing steps — searching, validating, creating — run on every tab entry
 * and every pull-to-refresh, so mapping them to the full-screen `Preparing` unconditionally is how a
 * healthy cached library became "Validating storage channel…" for the length of a network round trip,
 * over and over, and how the pull-to-refresh indicator ended up unreachable behind it.
 */
class CloudUiStateTest {
    private val zone = ZoneId.of("UTC")

    private fun media(id: Long) = CloudMedia(
        messageId = id,
        chatId = CHAT_ID,
        type = MediaType.Photo,
        mimeType = "",
        fileName = "",
        sizeBytes = 10,
        dateSeconds = 1_790_000_000L,
        dateSource = CloudDateSource.TelegramMessage,
        width = 0,
        height = 0,
        durationSeconds = null,
        remoteFileId = "R$id",
        previewRemoteFileId = "P$id",
        caption = "",
        contentHash = "",
    )

    private val cached = listOf(media(1L), media(2L))

    private fun initFor(step: CloudUiState.Preparing.Step): CloudInitState = when (step) {
        CloudUiState.Preparing.Step.Searching -> CloudInitState.SearchingChannel
        CloudUiState.Preparing.Step.Validating -> CloudInitState.ValidatingChannel
        CloudUiState.Preparing.Step.Creating -> CloudInitState.CreatingChannel
    }

    @Test
    fun `a cached library is never replaced by a preparing spinner`() {
        for (step in CloudUiState.Preparing.Step.entries) {
            val library = deriveCloudState(initFor(step), cached, totalCount = 2, counts = emptyList(), zone = zone)
                as? CloudUiState.Library

            assertTrue("the rows stay on screen while $step runs", library != null)
            assertTrue(
                "the check is announced as refresh work, not as an unreachable Telegram",
                library!!.refreshing,
            )
            assertFalse("nothing has said Telegram was unreachable yet", library.fromCache)
            assertEquals(2, library.totalCount)
            assertFalse("the window already holds the whole index", library.hasMoreToLoad)
        }
    }

    @Test
    fun `the preparing spinner is reserved for a screen with nothing to show`() {
        for (step in CloudUiState.Preparing.Step.entries) {
            assertEquals(
                CloudUiState.Preparing(step),
                deriveCloudState(initFor(step), emptyList(), totalCount = 0, counts = emptyList(), zone = zone),
            )
        }
    }

    /** Already the shape before the preparing fix; pinned so the two cannot quietly drift apart. */
    @Test
    fun `a scan over a cached library keeps the rows and announces the refresh`() {
        val library = deriveCloudState(
            CloudInitState.Scanning(2),
            cached,
            totalCount = 2,
            counts = emptyList(),
            zone = zone,
        ) as CloudUiState.Library

        assertTrue(library.refreshing)
        assertFalse(library.fromCache)
        assertEquals(2, library.totalCount)
    }

    private companion object {
        const val CHAT_ID = 55_000_000_000L
    }
}

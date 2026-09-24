package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.telegram.CloudAssociation
import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudHistoryPage
import com.lumovault.app.domain.telegram.CloudInitState
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three rules that decide whether a cloud scan is safe, checked against fakes rather than against
 * a live account: the walk terminates, an interrupted walk never prunes, and a chat id belonging to
 * another Telegram account is never reused.
 *
 * Each one is a data-loss bug if it breaks silently, and none of them is observable from the UI.
 */
/** File level, because the nested fakes below are not `inner` and cannot reach an outer property. */
private const val CHAT_ID = 55_000_000_000L

class SynchronizeCloudUseCaseTest {
    private fun media(id: Long) = CloudMedia(
        messageId = id,
        chatId = CHAT_ID,
        type = MediaType.Photo,
        mimeType = "",
        fileName = "",
        sizeBytes = 10,
        dateSeconds = 1_790_000_000L,
        dateSource = com.lumovault.app.domain.model.CloudDateSource.TelegramMessage,
        width = 0,
        height = 0,
        durationSeconds = null,
        remoteFileId = "R$id",
        previewRemoteFileId = "P$id",
        caption = "",
    )

    /** Pages newest-first; [pages] is consumed in order, and the last one ends the walk. */
    private class FakeTelegram(
        private val pages: List<List<Long>>,
        var userId: Long = 11L,
        var existingChannel: Long? = CHAT_ID,
        var verdict: CloudChannelVerdict = CloudChannelVerdict.Valid,
        var usable: Boolean = true,
    ) : TelegramCloudRepository {
        val requestedFrom = mutableListOf<Long>()
        var created = 0

        override val isUsable: Boolean get() = usable
        override suspend fun accountUserId(): Long = userId
        override suspend fun findStorageChannel(): Long? = existingChannel
        override suspend fun validateChannel(chatId: Long): CloudChannelVerdict = verdict

        override suspend fun createStorageChannel(): Long {
            created += 1
            return CHAT_ID
        }

        override suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): CloudHistoryPage {
            requestedFrom += fromMessageId
            val page = pages.getOrElse(requestedFrom.size - 1) { emptyList() }
            return CloudHistoryPage(
                media = page.map { media(it) },
                oldestMessageId = page.minOrNull() ?: 0L,
                reachedBeginning = page.isEmpty() || page.minOrNull() == 1L,
                totalRemoteCount = page.size,
            )
        }
    }

    private class FakeIndex(initial: CloudAssociation? = null) : CloudIndexRepository {
        var saved = initial
        var cleared = 0
        val pagesWritten = mutableListOf<List<Long>>()
        var finishedWith: Pair<Long, Boolean>? = null

        override fun observeWindow(limit: Int): Flow<List<CloudMedia>> = flowOf(emptyList())
        override fun observeCount(): Flow<Int> = flowOf(saved?.let { 1 } ?: 0)
        override fun observeTypeCounts(): Flow<List<CloudTypeCount>> = flowOf(emptyList())
        override suspend fun currentCount(): Int = pagesWritten.sumOf { it.size }
        override suspend fun savePage(chatId: Long, scanId: Long, items: List<CloudMedia>) {
            pagesWritten += items.map { it.messageId }
        }

        override suspend fun finishScan(scanId: Long, association: CloudAssociation, prune: Boolean): Int {
            finishedWith = scanId to prune
            saved = association
            return 0
        }

        override suspend fun association(): CloudAssociation? = saved
        override suspend fun saveAssociation(association: CloudAssociation) {
            saved = association
        }

        override suspend fun replaceAssociation(association: CloudAssociation) {
            cleared += 1
            saved = association
        }

        override suspend fun dropAssociation() {
            cleared += 1
            saved = null
        }
    }

    private fun useCase(telegram: TelegramCloudRepository, index: CloudIndexRepository) =
        SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 1_000L },
            pageSize = 3,
        )

    @Test
    fun walkTerminatesAtTheOldestMessageAndNeverLoops() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(9, 8, 7), listOf(6, 5, 4), listOf(3, 2, 1)))
        val index = FakeIndex()

        val adopted = useCase(telegram, index).synchronize()

        assertEquals(CHAT_ID, adopted?.chatId)
        // One request per page, starting at the newest, then each page's oldest id.
        assertEquals(listOf(0L, 7L, 4L), telegram.requestedFrom)
        assertEquals(listOf(listOf(9L, 8L, 7L), listOf(6L, 5L, 4L), listOf(3L, 2L, 1L)), index.pagesWritten)
        assertEquals(1_000L to true, index.finishedWith)
        assertNull("a completed scan leaves no resume cursor", index.saved?.lastScannedMessageId?.takeIf { it != 0L })
    }

    @Test
    fun stalledPageEndsTheWalkInsteadOfLoopingForever() = runBlocking {
        // A page that fails to move the cursor backwards must stop the walk: without the guard the
        // same ids would be requested until the app is killed.
        val telegram = FakeTelegram(pages = listOf(listOf(9, 8, 7), listOf(9, 8, 7)))
        val index = FakeIndex()

        useCase(telegram, index).synchronize()

        assertEquals(2, telegram.requestedFrom.size)
    }

    @Test
    fun resumedScanKeepsRowsItNeverReached() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(4, 3, 2), listOf(1)))
        val index = FakeIndex(
            initial = CloudAssociation(
                chatId = CHAT_ID,
                ownerUserId = 11L,
                protocolVersion = LumoVaultStorageProtocol.VERSION,
                lastScannedMessageId = 7L,
            ),
        )

        useCase(telegram, index).synchronize()

        assertEquals(7L, telegram.requestedFrom.first())
        val (scanId, prune) = index.finishedWith!!
        assertEquals(1_000L, scanId)
        assertFalse("a resumed walk must not prune rows above its cursor", prune)
    }

    @Test
    fun associationFromAnotherAccountIsDroppedAndRediscovered() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(1)), userId = 22L)
        val index = FakeIndex(
            initial = CloudAssociation(
                chatId = CHAT_ID,
                ownerUserId = 11L,
                protocolVersion = LumoVaultStorageProtocol.VERSION,
            ),
        )

        val adopted = useCase(telegram, index).synchronize()

        assertEquals("the previous account's association was invalidated", 1, index.cleared)
        assertEquals(22L, adopted?.ownerUserId)
        assertEquals("no channel was created for an account that already has one", 0, telegram.created)
    }

    @Test
    fun invalidSavedChannelFallsBackToDiscoveryAndCreation() = runBlocking {
        val telegram = FakeTelegram(
            pages = listOf(listOf(1)),
            existingChannel = null,
            verdict = CloudChannelVerdict.NotFound,
        )
        val index = FakeIndex(
            initial = CloudAssociation(chatId = CHAT_ID, ownerUserId = 11L, protocolVersion = 1),
        )

        val adopted = useCase(telegram, index).synchronize()

        assertTrue("a deleted channel is recreated only after discovery found nothing", telegram.created == 1)
        assertEquals(11L, adopted?.ownerUserId)
    }

    @Test
    fun unavailableTelegramAndSignedOutSessionNeverTouchTheIndex() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(1)), usable = false)
        val index = FakeIndex()

        val usecase = SynchronizeCloudUseCase(telegram, index, isAuthenticated = { true }, nowSeconds = { 5L })
        assertNull(usecase.synchronize())
        assertEquals(CloudInitState.TelegramUnavailable, usecase.state.value)

        val signedOut = SynchronizeCloudUseCase(
            FakeTelegram(pages = listOf(listOf(1))),
            FakeIndex(),
            isAuthenticated = { false },
            nowSeconds = { 5L },
        )
        assertNull(signedOut.synchronize())
        assertEquals(CloudInitState.WaitingForTelegram, signedOut.state.value)
        assertTrue("no pages were requested from a session that is not ready", index.pagesWritten.isEmpty())
    }
}

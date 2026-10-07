package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.CloudIndexRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.telegram.ChannelDiscovery
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudFailureException
import com.lumovault.app.domain.telegram.CloudAssociation
import com.lumovault.app.domain.telegram.CloudChannelVerdict
import com.lumovault.app.domain.telegram.CloudHistoryPage
import com.lumovault.app.domain.telegram.CloudInitState
import com.lumovault.app.domain.telegram.LumoVaultStorageProtocol
import com.lumovault.app.domain.telegram.TelegramCloudRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        contentHash = "",
    )

    /** Pages newest-first; [pages] is consumed in order, and the last one ends the walk. */
    inner class FakeTelegram(
        private val pages: List<List<Long>>,
        var userId: Long = 11L,
        var discovery: ChannelDiscovery = ChannelDiscovery.Found(CHAT_ID),
        var verdict: CloudChannelVerdict = CloudChannelVerdict.Valid,
        var usable: Boolean = true,
        /** Thrown instead of answering, for the cases where Telegram is the thing that failed. */
        var discoveryFailure: Exception? = null,
    ) : TelegramCloudRepository {
        val requestedFrom = mutableListOf<Long>()
        var created = 0
        var discoveries = 0

        /** When set, discovery holds here until completed — how a test keeps one adoption open. */
        var discoveryGate: CompletableDeferred<Unit>? = null

        override val isUsable: Boolean get() = usable
        override suspend fun accountUserId(): Long = userId

        override suspend fun discoverStorageChannel(): ChannelDiscovery {
            discoveries += 1
            discoveryFailure?.let { throw it }
            discoveryGate?.await()
            return discovery
        }

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

    inner class FakeIndex(initial: CloudAssociation? = null) : CloudIndexRepository {
        var saved = initial
        var cleared = 0
        val pagesWritten = mutableListOf<List<Long>>()
        var finishedWith: Pair<Long, Boolean>? = null

        override fun observeWindow(limit: Int): Flow<List<CloudMedia>> = flowOf(emptyList())

        // Unused by the walk: select all and the Cloud selection actions are asserted in
        // `CloudSelectAllRealSqlTest`, and this fake only has to keep the interface it
        // implements the real one.
        override suspend fun allIds(): List<Long> = emptyList()

        override suspend fun itemsFor(ids: Collection<Long>): List<CloudMedia> = emptyList()
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

        /**
         * The flow form, answered from the same value. Nothing in this file observes it — the walk acts on
         * the one-shot [association] — and the screen that does observe it tests its own decision rule in
         * `BackupScreenRulesTest`, so this exists to keep the interface this fake implements the real one.
         */
        override fun observeAssociation(): Flow<CloudAssociation?> = flowOf(saved)

        // Recognition's two reads, unused by the walk: the fake answers them so the interface it
        // implements stays the real one rather than a copy frozen at Phase 4.
        override suspend fun remoteBackupFor(contentHash: String): RemoteBackup? = null
        override suspend fun unrecognizedRemoteCount(): Int = 0

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
            discovery = ChannelDiscovery.Absent,
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
    fun twoCallersAtOnceAdoptOneChannelRatherThanEachCreatingTheirOwn() = runBlocking<Unit> {
        val telegram = FakeTelegram(pages = listOf(emptyList()), discovery = ChannelDiscovery.Absent)
        telegram.discoveryGate = CompletableDeferred()
        val index = FakeIndex()
        val subject = useCase(telegram, index)

        val screen = launch { subject.synchronize() }
        yield()
        assertEquals("the first caller is inside discovery", 1, telegram.discoveries)

        val worker = launch { subject.ensureChannel() }
        yield()
        assertEquals(
            "the second caller must not run its own discovery: two 'absent' answers are two " +
                "channels, and the second creation orphans every backup in the first — which no " +
                "later sync repairs, because the association now points at the empty new one",
            1,
            telegram.discoveries,
        )

        requireNotNull(telegram.discoveryGate).complete(Unit)
        screen.join()
        worker.join()

        assertEquals("exactly one channel exists", 1, telegram.created)
        assertEquals(CHAT_ID, index.saved?.chatId)
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
        assertTrue("no pages were asked for by a session that is not ready", index.pagesWritten.isEmpty())
    }

    /**
     * The reinstall path, which is the only way this flow can end with the user's library on screen or in
     * the wrong channel.
     *
     * Every case below asserts on `created` first, because that is the number that cannot be wrong: an
     * adoption that works and a creation that also happens leaves the account with two storage channels and
     * the association pointing at the empty one, which no later sync repairs. The other half — that discovery
     * can *refuse* to conclude — is what the tests that expect `InProgress` are for, since the bug this
     * replaced was exactly that refusal being read as an answer.
     */
    @Test
    fun aStillValidSavedAssociationIsUsedWithoutAskingTelegramToSearch() = runBlocking {
        val telegram = FakeTelegram(
            pages = listOf(listOf(1)),
            discovery = ChannelDiscovery.Absent,
        )
        val index = FakeIndex(
            initial = CloudAssociation(chatId = CHAT_ID, ownerUserId = 11L, protocolVersion = 1),
        )
        val events = mutableListOf<String>()
        val usecase = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
            pageSize = 3,
            recover = { events += it },
        )

        val adopted = usecase.synchronize()

        assertEquals(CHAT_ID, adopted?.chatId)
        assertEquals("a warm association is not a reason to search", 0, telegram.discoveries)
        assertEquals(0, telegram.created)
        assertTrue(events.contains("CLOUD_CHANNEL_ASSOCIATION_FOUND chat_id=$CHAT_ID"))
    }

    @Test
    fun aReinstalledAccountAdoptsTheChannelItFindsAndBuildsItsIndexFromIt() = runBlocking {
        // Nothing local: no association, no index. The account's channel and its messages are all there is.
        val telegram = FakeTelegram(pages = listOf(listOf(9, 8, 7), listOf(6, 5, 4), listOf(3, 2, 1)))
        val index = FakeIndex()
        val events = mutableListOf<String>()
        val usecase = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 1_000L },
            pageSize = 3,
            recover = { events += it },
        )

        val adopted = usecase.synchronize()

        assertEquals(CHAT_ID, adopted?.chatId)
        assertEquals(1, telegram.discoveries)
        assertEquals("the old channel was found, so nothing was built", 0, telegram.created)
        assertEquals(
            "history is re-indexed from the channel, newest page first",
            listOf(listOf(9L, 8L, 7L), listOf(6L, 5L, 4L), listOf(3L, 2L, 1L)),
            index.pagesWritten,
        )
        assertEquals("and the adopted id is persisted before anything else can ask", CHAT_ID, index.saved?.chatId)
        assertTrue(events.contains("CLOUD_CHANNEL_RECOVERED chat_id=$CHAT_ID"))
        assertFalse("creation was never permitted", events.contains("CLOUD_CHANNEL_CREATION_ALLOWED"))
    }

    @Test
    fun aFreshTdLibCacheThatHasNotFinishedLoadingDiscoversNothingAndCreatesNothing() = runBlocking {
        val telegram = FakeTelegram(
            pages = listOf(listOf(1)),
            discovery = ChannelDiscovery.InProgress(ChannelDiscovery.Reason.ChatListLoading),
        )
        val index = FakeIndex()
        val events = mutableListOf<String>()
        val usecase = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
            recover = { events += it },
        )

        val adopted = usecase.synchronize()

        assertNull("an inconclusive search is not a green light", adopted)
        assertEquals(0, telegram.created)
        assertTrue("nothing is indexed when there is no channel to index", index.pagesWritten.isEmpty())
        assertEquals(CloudInitState.Offline, usecase.state.value)
        assertTrue(events.any { it.startsWith("CLOUD_CHANNEL_DISCOVERY_RETRY") })
    }

    @Test
    fun aTelegramFailureDuringDiscoveryIsRetriedAndNeverTreatedAsAbsence() = runBlocking {
        val telegram = FakeTelegram(
            pages = listOf(listOf(1)),
            discoveryFailure = CloudFailureException(CloudFailure(CloudFailure.Kind.RateLimited, 420)),
        )
        val index = FakeIndex()
        val usecase = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
        )

        assertNull(usecase.synchronize())
        assertEquals(0, telegram.created)
        assertEquals(CloudInitState.Offline, usecase.state.value)
    }

    @Test
    fun aSearchThatConcludedTheAccountHasNoChannelCreatesExactlyOne() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(1)), discovery = ChannelDiscovery.Absent)
        val index = FakeIndex()
        val events = mutableListOf<String>()

        val adopted = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
            recover = { events += it },
        ).synchronize()

        assertEquals(1, telegram.created)
        assertEquals(CHAT_ID, adopted?.chatId)
        assertTrue(events.contains("CLOUD_CHANNEL_CREATION_ALLOWED"))
        assertTrue(events.contains("CLOUD_CHANNEL_CREATED chat_id=$CHAT_ID"))
    }

    @Test
    fun recoveryRepeatedAfterARestartKeepsUsingTheSameChannel() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(1)))
        val index = FakeIndex()
        val usecase = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
        )

        val first = usecase.synchronize()
        val second = usecase.synchronize()

        assertEquals(first?.chatId, second?.chatId)
        assertEquals("a second pass over the same account builds nothing", 0, telegram.created)
    }

    @Test
    fun aCallerThatForbiddenCreationIsToldTheChannelIsGoneRatherThanGivenANewOne() = runBlocking {
        val telegram = FakeTelegram(pages = listOf(listOf(1)), discovery = ChannelDiscovery.Absent)
        val index = FakeIndex()

        val adopted = SynchronizeCloudUseCase(
            telegram = telegram,
            index = index,
            isAuthenticated = { true },
            nowSeconds = { 5L },
        ).synchronize(createIfMissing = false)

        assertNull(adopted)
        assertEquals(0, telegram.created)
    }

    @Test
    fun aCancelledDiscoveryCreatesNothingOnTheWayOut() = runBlocking {
        val telegram = FakeTelegram(
            pages = listOf(listOf(1)),
            discoveryFailure = kotlinx.coroutines.CancellationException("user left the screen"),
        )
        val index = FakeIndex()

        val thrown = runCatching {
            SynchronizeCloudUseCase(
                telegram = telegram,
                index = index,
                isAuthenticated = { true },
                nowSeconds = { 5L },
            ).synchronize()
        }.exceptionOrNull()

        assertTrue("cancellation must not be swallowed into a state", thrown is kotlinx.coroutines.CancellationException)
        assertEquals("and it must not leave a channel behind", 0, telegram.created)
    }

    @Test
    fun theQueueAdoptsAChannelAndReadsNoHistoryToGetIt() = runBlocking {
        // The step the upload pass was missing: a phone that finished onboarding and never opened the
        // Cloud tab had rows in `queued` and no chat id, and nothing in the app could ever have drained
        // them. Paging the history is deliberately not part of this.
        val telegram = FakeTelegram(pages = listOf(listOf(9, 8, 7)))
        val index = FakeIndex()

        val adopted = useCase(telegram, index).ensureChannel()

        assertEquals(CHAT_ID, adopted?.chatId)
        assertEquals(CHAT_ID, index.saved?.chatId)
        assertEquals("no history page was asked for", emptyList<Long>(), telegram.requestedFrom)
    }

    @Test
    fun theQueueUsesASavedChannelBeforeItAsksTelegramForOne() = runBlocking {
        val telegram = FakeTelegram(pages = emptyList(), discovery = ChannelDiscovery.Absent)
        val index = FakeIndex(
            initial = CloudAssociation(chatId = CHAT_ID, ownerUserId = 11L, protocolVersion = 1),
        )

        assertEquals(CHAT_ID, useCase(telegram, index).ensureChannel()?.chatId)
        assertEquals("a warm association is not a reason to search", 0, telegram.discoveries)
        assertEquals(0, telegram.created)
    }

    @Test
    fun theQueueCreatesOnlyOnAnAnswerAndNeverOnASilence() = runBlocking {
        val absent = FakeTelegram(pages = emptyList(), discovery = ChannelDiscovery.Absent)
        val absentIndex = FakeIndex()
        assertEquals(CHAT_ID, useCase(absent, absentIndex).ensureChannel()?.chatId)
        assertEquals("discovery concluded there was nothing, so this is the one branch that may create", 1, absent.created)

        val loading = FakeTelegram(
            pages = emptyList(),
            discovery = ChannelDiscovery.InProgress(ChannelDiscovery.Reason.ChatListLoading),
        )
        val loadingIndex = FakeIndex()

        assertNull(useCase(loading, loadingIndex).ensureChannel())
        assertEquals("an empty answer is not an answer about Telegram", 0, loading.created)
        assertNull("and nothing is written on the way out", loadingIndex.saved)
    }
}

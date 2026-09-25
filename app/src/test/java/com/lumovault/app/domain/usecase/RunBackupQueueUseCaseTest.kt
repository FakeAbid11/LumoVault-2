package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.MediaSourceStager
import com.lumovault.app.domain.backup.StagedSource
import com.lumovault.app.domain.backup.TelegramUploadRepository
import com.lumovault.app.domain.backup.UploadEvent
import com.lumovault.app.domain.backup.UploadRequest
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue pass, end to end, with every collaborator faked.
 *
 * What is worth testing here is not whether a send succeeds — that is Telegram's side and belongs on a
 * device — but the promises this engine makes about state: a refused item is not tried twice in one
 * pass, a failure is recorded against one item and no more, a stage that produced no file still
 * produces a state, and a row only reaches `BACKED_UP` through a message id an actual send returned.
 */
class RunBackupQueueUseCaseTest {
    private val queue = FakeQueue()
    private val upload = FakeUpload()
    private val stager = FakeStager()

    private fun useCase(channel: Long = CHANNEL) = RunBackupQueueUseCase(
        queue = queue,
        upload = upload,
        stager = stager,
        resolveChannel = { channel },
    )

    @Test
    fun oneItemGoesThroughPreparingAndUploadingToDoneAndKeepsTheMessageId() = runBlocking {
        queue.given(request(1L))
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9001L)))

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 1, failed = 0, deferred = false), outcome)
        assertEquals(listOf(UploadState.Preparing, UploadState.Uploading), queue.statesOf(1L))
        assertEquals(listOf(CHANNEL to 9001L), queue.backedUp)
        assertEquals("the copy is removed once it has been sent", listOf("staged-1"), stager.discarded)
    }

    @Test
    fun aRefusalIsRecordedAgainstTheItemThatWasRefusedAndNotAgainstItsNeighbours() = runBlocking {
        queue.given(request(1L), request(2L))
        upload.script(listOf(UploadEvent.Refused(BackupFailure(BackupFailureKind.Rejected))))
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9002L)))

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 1, failed = 1, deferred = true), outcome)
        assertEquals(listOf(1L), queue.released.map { it.first.mediaStoreId })
        assertEquals(listOf(CHANNEL to 9002L), queue.backedUp)
    }

    @Test
    fun anItemRefusedThisPassIsNotTriedAgainThisPass() = runBlocking {
        queue.given(request(1L))
        upload.script(listOf(UploadEvent.Refused(BackupFailure(BackupFailureKind.Network))))

        val outcome = useCase().run()

        // One send only. Retrying a network failure in a tight loop is not backoff — it is a way to turn
        // one dropped connection into a hundred, and `deferred` is how the caller learns to wait.
        assertEquals(1, upload.started)
        assertEquals(QueueRun.Done(sent = 0, failed = 1, deferred = true), outcome)
    }

    @Test
    fun aFailureBeforeTelegramStillSettlesTheRowAndSendsNothing() = runBlocking {
        queue.given(request(1L))
        stager.stageOutcomes += StagedSource.Unavailable(
            BackupFailure(BackupFailureKind.InsufficientSpace),
        )

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 0, failed = 1, deferred = true), outcome)
        assertEquals(
            "the row was claimed but never reached the send",
            listOf(UploadState.Preparing),
            queue.statesOf(1L),
        )
        assertEquals(0, upload.started)
        assertEquals(BackupFailureKind.InsufficientSpace, queue.released.single().second.kind)
    }

    @Test
    fun aQueueWithNoAdoptedChannelSendsNothingAnywhere() = runBlocking {
        queue.given(request(1L))

        assertEquals(QueueRun.NoChannel, useCase(channel = 0L).run())
        assertEquals(0, upload.started)
        assertEquals(emptyList<Long>(), queue.claims)
    }

    @Test
    fun aBuildWithoutTelegramDoesNotEvenReconcileTheQueue() = runBlocking {
        upload.usable = false

        assertEquals(QueueRun.TelegramUnavailable, useCase().run())
        assertEquals("nothing was claimed", emptyList<Long>(), queue.claims)
        assertEquals(0, queue.reconciles)
    }

    @Test
    fun everyPassStartsByReleasingRowsLeftMidFlightByTheLastProcess() = runBlocking {
        useCase().run()

        assertEquals(1, queue.reconciles)
    }

    @Test
    fun progressReachesTheCallerTogetherWithTheItemItBelongsTo() = runBlocking {
        queue.given(request(1L))
        upload.script(listOf(UploadEvent.Progress(0.5f), UploadEvent.Sent(CHANNEL, 9001L)))

        val seen = mutableListOf<BackupProgress>()
        useCase().run { seen += it }

        assertEquals(1, seen.size)
        assertEquals(1L, seen.single().mediaStoreId)
        assertEquals(0.5f, seen.single().fraction)
        assertEquals("IMG_1.jpg", seen.single().displayName)
    }

    @Test
    fun anUploadThatAnswersNothingIsSettledAsUnknownRatherThanLeftUploading() = runBlocking {
        queue.given(request(1L))
        upload.script(emptyList())

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 0, failed = 1, deferred = true), outcome)
        assertEquals(BackupFailureKind.Unknown, queue.released.single().second.kind)
        assertEquals("the row did move into uploading before the silence", listOf(UploadState.Preparing, UploadState.Uploading), queue.statesOf(1L))
    }

    @Test
    fun cancellingWithdrawsTheQueueThroughTheRepositoryItWasGiven() = runBlocking {
        queue.cancelResult = 3

        assertEquals(3, useCase().cancelPending())
    }

    @Test
    fun retryingFailedItemsReturnsHowManyWentBackIntoTheQueue() = runBlocking {
        // Only the count is asserted: deciding whether a non-zero count deserves a new pass is the
        // screen's business, and a ViewModel is not reachable from a JVM test.
        queue.requeueResult = 0
        assertEquals(0, useCase().retryFailed())

        queue.requeueResult = 2
        assertEquals(2, useCase().retryFailed())
    }

    private fun request(id: Long) = BackupRequest(
        mediaStoreId = id,
        mediaType = MediaType.Photo,
        mimeType = "image/jpeg",
        contentUri = "content://media/external/images/media/$id",
        displayName = "IMG_$id.jpg",
        expectedSizeBytes = 1024L,
        width = 4,
        height = 3,
        durationMillis = null,
        state = UploadState.Preparing,
        telegramChatId = CHANNEL,
        telegramMessageId = 0L,
        attempts = 0,
        failure = null,
    )

    private companion object {
        const val CHANNEL = 55_000_000_000L
    }
}

/**
 * The queue as the pass sees it: items handed out in order, and a log of every state written.
 *
 * [statesOf] records the sequence rather than the last value on purpose — "it ended in `BACKED_UP`" is
 * a weaker claim than "it went preparing, then uploading, then backed up", and the sequence is what a
 * regression would break.
 */
private class FakeQueue : BackupQueueRepository {
    private val pending = ArrayDeque<BackupRequest>()
    private val written = mutableMapOf<Long, MutableList<UploadState>>()

    val claims = mutableListOf<Long>()
    val backedUp = mutableListOf<Pair<Long, Long>>()
    val released = mutableListOf<Pair<BackupRequest, BackupFailure>>()
    var reconciles = 0
    var cancelResult = 0
    var requeueResult = 0

    fun given(vararg requests: BackupRequest) {
        requests.forEach { pending += it }
    }

    fun statesOf(id: Long): List<UploadState> = written[id].orEmpty()

    override suspend fun claimNext(chatId: Long): BackupRequest? {
        val next = pending.removeFirstOrNull() ?: return null
        claims += next.mediaStoreId
        write(next.mediaStoreId, UploadState.Preparing)
        return next
    }

    override suspend fun markUploading(mediaStoreId: Long) = write(mediaStoreId, UploadState.Uploading)

    override suspend fun markStaged(mediaStoreId: Long, path: String) = Unit

    override suspend fun markBackedUp(mediaStoreId: Long, chatId: Long, messageId: Long) {
        write(mediaStoreId, UploadState.BackedUp)
        backedUp += chatId to messageId
    }

    override suspend fun release(request: BackupRequest, failure: BackupFailure) {
        released += request to failure
    }

    override suspend fun reconcileInterrupted(): Int {
        reconciles += 1
        return 0
    }

    override suspend fun stagedPathOf(mediaStoreId: Long): String = ""

    override suspend fun cancelQueued(): Int = cancelResult

    override suspend fun requeueFailed(): Int = requeueResult

    override suspend fun enqueue(mediaStoreIds: Collection<Long>): Int = mediaStoreIds.size

    override fun observeStatesFor(mediaStoreIds: Collection<Long>): Flow<Map<Long, UploadState>> =
        flowOf(emptyMap())

    override fun observeSummary(): Flow<BackupQueueSummary> = flowOf(BackupQueueSummary())

    private fun write(id: Long, state: UploadState) {
        written.getOrPut(id) { mutableListOf() } += state
    }
}

private class FakeUpload : TelegramUploadRepository {
    var usable = true
    var started = 0

    private val script = ArrayDeque<List<UploadEvent>>()

    override val isUsable: Boolean get() = usable

    fun script(events: List<UploadEvent>) {
        script += events
    }

    override fun upload(chatId: Long, request: UploadRequest): Flow<UploadEvent> {
        started += 1
        assertTrue("an upload was attempted with no scripted answer", script.isNotEmpty())
        return flowOf(*script.removeFirst().toTypedArray())
    }
}

private class FakeStager : MediaSourceStager {
    val stageOutcomes = mutableListOf<StagedSource>()
    val discarded = mutableListOf<String>()
    private var produced = 0

    override fun usableSpaceBytes(): Long = 1L shl 30

    override suspend fun stage(contentUri: String, displayName: String): StagedSource {
        if (stageOutcomes.isNotEmpty()) return stageOutcomes.removeAt(0)
        produced += 1
        return StagedSource.Ready("staged-$produced", 1024L)
    }

    override fun discard(path: String) {
        discarded += path
    }

    override fun purgeStale() = Unit
}

package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupIdentityCandidate
import com.lumovault.app.domain.backup.BackupQueueRepository
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.BackupRequest
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.backup.MediaSourceStager
import com.lumovault.app.domain.backup.StagedSource
import com.lumovault.app.domain.backup.TelegramUploadRepository
import com.lumovault.app.domain.backup.UploadEvent
import com.lumovault.app.domain.backup.UploadRequest
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.RemoteBackup
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
 * device — but the promises this engine makes about state: a refused item is not tried twice in one pass,
 * a failure is recorded against one item and no more, a stage that produced no file still produces a
 * state, and a row only reaches `BACKED_UP` through a message id — either one an actual send returned or
 * one the remote index reported as already holding these bytes.
 *
 * Recognition is the real [RecognizeBackupUseCase] over fakes rather than a stub, because the two halves
 * are only meaningful together: the queue cannot deduplicate anything it has not been told the hash of,
 * and the manifest the message carries is produced by the same call that decided to send.
 */
class RunBackupQueueUseCaseTest {
    private val queue = FakeQueue()
    private val upload = FakeUpload()
    private val stager = FakeStager()
    private val cloud = FakeCloudIndexRepository()
    private val hasher = FakeMediaContentHasher()

    private fun useCase(channel: Long = CHANNEL) = RunBackupQueueUseCase(
        queue = queue,
        upload = upload,
        stager = stager,
        recognition = recognizer(),
        resolveChannel = { channel },
    )

    private fun recognizer() = RecognizeBackupUseCase(
        queue = queue,
        cloud = cloud,
        hasher = hasher,
        // A frozen clock: the pass under test never loops here, so the deadline only has to not fire.
        nanoTime = { 0L },
    )

    @Test
    fun oneItemGoesThroughPreparingAndUploadingToDoneAndKeepsTheMessageId() = runBlocking {
        queue.given(request(1L))
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9001L)))

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 1, failed = 0, deferred = false), outcome)
        assertEquals(
            "the row walked the whole path, not just its last value",
            listOf(UploadState.Preparing, UploadState.Uploading, UploadState.BackedUp),
            queue.statesOf(1L),
        )
        assertEquals(listOf(CHANNEL to 9001L), queue.backedUp)
        assertEquals("the copy is removed once it has been sent", listOf("staged-1"), stager.discarded)
    }

    @Test
    fun everyUploadCarriesTheManifestThatIdentifiesWhatItSent() = runBlocking {
        queue.given(request(1L))
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9001L)))

        useCase().run()

        val manifest = upload.requests.single().manifest
        assertEquals(FakeMediaContentHasher.KNOWN_HASH, manifest.contentHash)
        assertEquals(
            "the size is the byte count of the copy that is being sent",
            1024L,
            manifest.sizeBytes,
        )
        assertEquals(900L, manifest.modifiedSeconds)
        assertEquals("IMG_1.jpg", manifest.fileName)
        assertEquals(
            "the staged copy is what was hashed, so a provider read is not spent twice",
            listOf("staged-1"),
            hasher.fileReads,
        )
    }

    @Test
    fun anItemTheChannelAlreadyHoldsIsClosedWithoutAnUpload() = runBlocking {
        queue.given(request(1L))
        cloud.given(FakeMediaContentHasher.KNOWN_HASH, CHANNEL, 777L)

        val outcome = useCase().run()

        assertEquals(
            QueueRun.Done(sent = 0, failed = 0, deferred = false, deduplicated = 1),
            outcome,
        )
        assertEquals("nothing was sent", 0, upload.started)
        assertEquals(
            "the row closed against the message that already holds it",
            listOf(CHANNEL to 777L),
            queue.backedUp,
        )
        assertEquals(listOf("staged-1"), stager.discarded)
        assertEquals(
            "and it never entered uploading, because there was no upload",
            listOf(UploadState.Preparing, UploadState.BackedUp),
            queue.statesOf(1L),
        )
    }

    @Test
    fun anItemWithACurrentHashIsLookedUpWithoutBeingReadAgain() = runBlocking {
        queue.given(
            request(1L).copy(
                contentHash = FakeMediaContentHasher.KNOWN_HASH,
                contentSizeBytes = 1024L,
                contentModifiedSeconds = 900L,
            ),
        )
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9001L)))

        useCase().run()

        assertEquals(
            "a file whose size and timestamp have not moved is not read again just to be asked twice",
            emptyList<String>(),
            hasher.fileReads,
        )
        assertEquals(listOf(FakeMediaContentHasher.KNOWN_HASH), cloud.lookups)
        assertEquals(1, upload.started)
        assertEquals(
            FakeMediaContentHasher.KNOWN_HASH,
            upload.requests.single().manifest.contentHash,
        )
    }

    @Test
    fun anItemThatCannotBeHashedIsNotSentAndNotClaimedStored() = runBlocking {
        queue.given(request(1L))
        hasher.failNext(BackupFailureKind.SourceMissing)

        val outcome = useCase().run()

        assertEquals(QueueRun.Done(sent = 0, failed = 1, deferred = true), outcome)
        assertEquals(0, upload.started)
        assertEquals(emptyList<Pair<Long, Long>>(), queue.backedUp)
        assertEquals(BackupFailureKind.SourceMissing, queue.released.single().second.kind)
        assertEquals("the copy goes even when the hash did not", listOf("staged-1"), stager.discarded)
    }

    @Test
    fun aCopyOfADifferentSizeThanTheHashWasTakenAgainstIsNotSentUnderThatHash() = runBlocking {
        // The index said 1024 bytes, the file was hashed at 1024, and the copy that arrived is bigger:
        // the file moved between the scan and the send, so the recorded hash describes something else.
        queue.given(
            request(1L).copy(
                contentHash = FakeMediaContentHasher.KNOWN_HASH,
                contentSizeBytes = 1024L,
                contentModifiedSeconds = 900L,
            ),
        )
        stager.sizes += 4096L
        hasher.digest = shaHashOf('c')
        upload.script(listOf(UploadEvent.Sent(CHANNEL, 9001L)))

        useCase().run()

        assertEquals(
            "the hash was recomputed from what actually arrived, so the manifest cannot lie",
            shaHashOf('c'),
            upload.requests.single().manifest.contentHash,
        )
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
        assertEquals(
            "the row did move into uploading before the silence",
            listOf(UploadState.Preparing, UploadState.Uploading),
            queue.statesOf(1L),
        )
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
        modifiedSeconds = 900L,
        width = 4,
        height = 3,
        durationMillis = null,
        state = UploadState.Preparing,
        contentHash = "",
        contentSizeBytes = 0L,
        contentModifiedSeconds = 0L,
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
    val identities = mutableMapOf<Long, MediaIdentity>()

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

    override suspend fun identityCandidates(
        includeWholeLibrary: Boolean,
        limit: Int,
    ): List<BackupIdentityCandidate> = emptyList()

    override suspend fun recordIdentity(mediaStoreId: Long, identity: MediaIdentity) {
        identities[mediaStoreId] = identity
    }

    override suspend fun adoptRemote(mediaStoreId: Long, remote: RemoteBackup): Boolean {
        write(mediaStoreId, UploadState.BackedUp)
        backedUp += remote.chatId to remote.messageId
        return true
    }

    override suspend fun autoBackupCandidates(
        source: com.lumovault.app.domain.model.BackupSource,
        folders: List<String>,
        limit: Int,
    ): List<Long> = emptyList()

    override suspend fun residentBackupFor(remote: RemoteBackup, manifestHash: String): Long? = null

    override suspend fun recordRestored(
        mediaStoreId: Long,
        remote: RemoteBackup,
        identity: MediaIdentity,
    ): Boolean {
        write(mediaStoreId, UploadState.BackedUp)
        backedUp += remote.chatId to remote.messageId
        return true
    }

    override suspend fun revokeAssociation(mediaStoreId: Long): Boolean {
        write(mediaStoreId, UploadState.NotBackedUp)
        return true
    }

    override suspend fun hasQueuedWork(): Boolean = pending.isNotEmpty()

    private fun write(id: Long, state: UploadState) {
        written.getOrPut(id) { mutableListOf() } += state
    }
}

private class FakeUpload : TelegramUploadRepository {
    var usable = true
    var started = 0
    val requests = mutableListOf<UploadRequest>()

    private val script = ArrayDeque<List<UploadEvent>>()

    override val isUsable: Boolean get() = usable

    fun script(events: List<UploadEvent>) {
        script += events
    }

    override fun upload(chatId: Long, request: UploadRequest): Flow<UploadEvent> {
        started += 1
        requests += request
        assertTrue("an upload was attempted with no scripted answer", script.isNotEmpty())
        return flowOf(*script.removeFirst().toTypedArray())
    }
}

private class FakeStager : MediaSourceStager {
    val stageOutcomes = mutableListOf<StagedSource>()
    val discarded = mutableListOf<String>()
    val sizes = mutableListOf<Long>()
    private var produced = 0

    override fun usableSpaceBytes(): Long = 1L shl 30

    override suspend fun stage(contentUri: String, displayName: String): StagedSource {
        if (stageOutcomes.isNotEmpty()) return stageOutcomes.removeAt(0)
        produced += 1
        // Scripted sizes let a test make the copy disagree with the index, which is the one way a stale
        // hash can reach a send; anything unscribed is the size the index reported.
        val size = sizes.removeFirstOrNull() ?: DEFAULT_SIZE
        return StagedSource.Ready("staged-$produced", size)
    }

    override fun discard(path: String) {
        discarded += path
    }

    override fun purgeStale() = Unit

    private companion object {
        const val DEFAULT_SIZE = 1024L
    }
}

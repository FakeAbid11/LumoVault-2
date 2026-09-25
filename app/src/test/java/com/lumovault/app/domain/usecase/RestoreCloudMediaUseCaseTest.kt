package com.lumovault.app.domain.usecase

import com.lumovault.app.data.local.backup.FakeBackupQueueDao
import com.lumovault.app.data.local.backup.QueueClock
import com.lumovault.app.data.repository.BackupQueueRepositoryImpl
import com.lumovault.app.domain.backup.BackupFailure
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.ContentDigest
import com.lumovault.app.domain.backup.MediaContentHasher
import com.lumovault.app.domain.backup.MediaIdentity
import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.MediaRepository
import com.lumovault.app.domain.repository.RemoteBackup
import com.lumovault.app.domain.repository.RestoreRepository
import com.lumovault.app.domain.repository.SyncResult
import com.lumovault.app.domain.restore.CloudRestoreTarget
import com.lumovault.app.domain.restore.RestorableSource
import com.lumovault.app.domain.restore.RestoreFailure
import com.lumovault.app.domain.restore.RestoreFailureKind
import com.lumovault.app.domain.restore.RestoreJob
import com.lumovault.app.domain.restore.RestoreOutcome
import com.lumovault.app.domain.restore.RestoreState
import com.lumovault.app.domain.restore.RestoredMediaWriter
import com.lumovault.app.domain.restore.StoredMedia
import com.lumovault.app.domain.telegram.DownloadProgress
import com.lumovault.app.domain.telegram.OriginalDownload
import com.lumovault.app.domain.telegram.RemoteOriginal
import com.lumovault.app.domain.telegram.TelegramOriginalRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The restore flow, end to end, against the real queue semantics.
 *
 * The backup queue here is the production repository over [FakeBackupQueueDao] rather than another fake,
 * because the one claim this phase makes that must not be soft is "a restored file is not uploaded again".
 * That claim lives in the interaction between a restore and the queue's own rules — which row states may be
 * settled, which joins count as present on the device — and testing it against a fake that agrees with
 * whatever the caller wants would prove nothing.
 *
 * What is faked is everything outside this process's decisions: TDLib's bytes, MediaStore's row, the
 * scanner. Their contracts are asserted as calls made or refused, which is the honest limit of a build
 * server. That a restored video plays, and lands in the gallery, are device questions.
 */
class RestoreCloudMediaUseCaseTest {
    private val clock = QueueClock()
    private val dao = FakeBackupQueueDao()
    private val queue = BackupQueueRepositoryImpl(dao, clock::now, attemptCap = 3)

    private val restores = FakeRestores()
    private val downloads = FakeDownloads()
    private val writer = FakeWriter()
    private val media = FakeMedia()
    private val hasher = FakeHasher()

    private fun useCase(scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)) =
        RestoreCloudMediaUseCase(
            restores = restores,
            downloads = downloads,
            writer = writer,
            queue = queue,
            media = media,
            hasher = hasher,
            scope = scope,
        )

    @Test
    fun aRestoredFileIsSettledAgainstTheMessageItCameFromInsteadOfBeingOfferedAgain() = runBlocking<Unit> {
        dao.withItem(RESTORED_ID)
        media.indexed = localRow(RESTORED_ID)
        writer.result = StoredMedia.Ready(RESTORED_URI, RESTORED_ID, SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)

        val outcome = useCase().restore(target())

        assertEquals(
            "the file landed, so this is a success, and the hash recorded is of the bytes that arrived",
            RestoreOutcome.Restored(
                mediaStoreId = RESTORED_ID,
                contentHash = LANDED_HASH,
                byteIdenticalToUpload = null,
                recordedAsBackedUp = true,
                indexed = true,
            ),
            outcome,
        )

        val row = dao.row(RESTORED_ID)
        assertEquals("backed_up", row.state)
        assertEquals(CHAT, row.chatId)
        assertEquals(MESSAGE, row.messageId)
        assertEquals(LANDED_HASH, row.contentHash)
        assertEquals(SIZE, row.contentSizeBytes)
        assertFalse(
            "a restored item must not be waiting for a worker, which is how a channel gets a second copy",
            queue.hasQueuedWork(),
        )
        assertEquals("TDLib's cache copy is released once the file is filed", listOf(7), downloads.released)
        assertEquals(1, media.syncs)
        assertEquals(RestoreState.Completed, restores.stateOf(CHAT, MESSAGE))
    }

    @Test
    fun contentAlreadyOnTheDeviceIsNeverDownloadedAgain() = runBlocking<Unit> {
        dao.withItem(LOCAL_ID)
        queue.recordRestored(
            mediaStoreId = LOCAL_ID,
            remote = RemoteBackup(CHAT, MESSAGE),
            identity = MediaIdentity(LANDED_HASH, SIZE, MODIFIED),
        )
        downloads.result = OriginalDownload.Ready(fileId = 1, path = TDLIB_PATH, sizeBytes = SIZE)

        val outcome = useCase().restore(target())

        assertEquals(RestoreOutcome.AlreadyOnDevice(LOCAL_ID), outcome)
        assertEquals("no bytes were asked for", 0, downloads.asks)
        assertEquals("no file was written", 0, writer.stores)
    }

    @Test
    fun theSameHashOnAnotherItemCountsAsAlreadyHere() = runBlocking<Unit> {
        // A photo that reached this phone twice — once sent, once restored from a different message — is the
        // duplicate the check exists for, and the manifest hash is the only thing that can see it.
        dao.withItem(LOCAL_ID)
        queue.recordIdentity(LOCAL_ID, MediaIdentity(LANDED_HASH, SIZE, MODIFIED))
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)

        val outcome = useCase().restore(target(manifestHash = LANDED_HASH))

        assertEquals(RestoreOutcome.AlreadyOnDevice(LOCAL_ID), outcome)
        assertEquals(0, downloads.asks)
    }

    @Test
    fun theManifestHashIsComparedAgainstAndNeverWrittenAsThisFilesIdentity() = runBlocking<Unit> {
        dao.withItem(RESTORED_ID)
        media.indexed = localRow(RESTORED_ID)
        writer.result = StoredMedia.Ready(RESTORED_URI, RESTORED_ID, SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)

        // Telegram's own re-encode: the message declares a hash the downloaded bytes do not carry.
        val restored = useCase().restore(target(manifestHash = UPLOADED_HASH)) as RestoreOutcome.Restored

        assertFalse(
            "the difference has to be reportable, because it is why the bytes are not the ones sent",
            restored.byteIdenticalToUpload!!,
        )
        assertEquals(LANDED_HASH, restored.contentHash)
        assertEquals(
            "the queue records what landed; writing the manifest's hash here would claim a match never measured",
            LANDED_HASH,
            dao.row(RESTORED_ID).contentHash,
        )
    }

    @Test
    fun aDownloadThatRefusesWritesNoFileAndRecordsTheReason() = runBlocking<Unit> {
        downloads.result = OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.RateLimited))

        val outcome = useCase().restore(target())

        assertEquals(RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.RateLimited)), outcome)
        assertEquals(0, writer.stores)
        assertEquals(RestoreState.Failed, restores.stateOf(CHAT, MESSAGE))
        assertEquals(RestoreFailureKind.RateLimited, restores.lastFailure)
        assertTrue("nothing arrived, so there is no cache file to release", downloads.released.isEmpty())
    }

    @Test
    fun bytesThatDisagreeWithTheirOwnLengthAreNotFiled() = runBlocking<Unit> {
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        // The digest disagrees with the file about how big it is: the bytes changed while they were read.
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE - 1)

        val outcome = useCase().restore(target())

        assertEquals(RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.Incomplete)), outcome)
        assertEquals(0, writer.stores)
        assertEquals("TDLib's copy is released even when the restore fails", listOf(7), downloads.released)
        assertEquals(RestoreState.Failed, restores.stateOf(CHAT, MESSAGE))
    }

    @Test
    fun anUnreadableDownloadIsReportedAsIncompleteRatherThanAsASuccess() = runBlocking<Unit> {
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        hasher.digest = ContentDigest.Unavailable(BackupFailure(BackupFailureKind.SourceUnreadable))

        assertEquals(
            RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.Incomplete)),
            useCase().restore(target()),
        )
        assertEquals(0, writer.stores)
        assertEquals(listOf(7), downloads.released)
    }

    @Test
    fun aRejectedMediaStoreRowStillReleasesTheCacheAndReportsWhy() = runBlocking<Unit> {
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        writer.result = StoredMedia.Refused(RestoreFailure(RestoreFailureKind.SaveRejected))

        val outcome = useCase().restore(target())

        assertEquals(RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.SaveRejected)), outcome)
        assertEquals(listOf(7), downloads.released)
        assertEquals(RestoreState.Failed, restores.stateOf(CHAT, MESSAGE))
        assertEquals(0, media.syncs)
    }

    @Test
    fun aVolumeThatCannotHoldTheFileIsRefusedBeforeTdLibIsAsked() = runBlocking<Unit> {
        writer.roomAvailable = false
        downloads.result = OriginalDownload.Ready(fileId = 1, path = TDLIB_PATH, sizeBytes = SIZE)

        val outcome = useCase().restore(target())

        assertEquals(RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.InsufficientSpace)), outcome)
        assertEquals("the expensive thing did not start", 0, downloads.asks)
        assertEquals("no job was claimed", 0, restores.takes)
    }

    @Test
    fun aBuildWithoutTelegramRefusesInsteadOfFailingLater() = runBlocking<Unit> {
        downloads.usable = false

        assertEquals(
            RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.NotAuthenticated)),
            useCase().restore(target()),
        )
        assertEquals(0, restores.takes)
    }

    @Test
    fun aCloudRecordWithNothingToFetchIsNotARequest() = runBlocking<Unit> {
        val outcome = useCase().restore(target(remoteFileId = "   "))

        assertEquals(RestoreOutcome.Refused(RestoreFailure(RestoreFailureKind.SourceGone)), outcome)
        assertEquals(0, downloads.asks)
    }

    @Test
    fun aFileTheIndexHasNotSeenYetIsRestoredButNotClaimedAsIndexed() = runBlocking<Unit> {
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        writer.result = StoredMedia.Ready(RESTORED_URI, RESTORED_ID, SIZE)
        media.indexed = null // MediaStore has the file; the scan has not caught up yet.

        val restored = useCase().restore(target()) as RestoreOutcome.Restored

        assertFalse("the library does not list it yet", restored.indexed)
        assertFalse("so nothing is asserted about its backup state", restored.recordedAsBackedUp)
        assertNull(dao.rowOrNull(RESTORED_ID))
        assertEquals(
            "the job still records the id it created, so a later scan can finish the job",
            RESTORED_ID,
            restores.completedMediaStoreId,
        )
    }

    @Test
    fun oneTapStartsOneTransfer() = runBlocking<Unit> {
        dao.withItem(RESTORED_ID)
        media.indexed = localRow(RESTORED_ID)
        writer.result = StoredMedia.Ready(RESTORED_URI, RESTORED_ID, SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        val gate = CompletableDeferred<Unit>()
        downloads.gate = gate

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val useCase = useCase(scope)
        val first = scope.launch { useCase.restore(target()) }

        // Unconfined ran the body as far as the gate, so the first transfer is in flight at this line.
        assertTrue(downloads.insideDownload)
        assertEquals(RestoreOutcome.InProgress, useCase.restore(target()))
        assertEquals(RestoreOutcome.InProgress, useCase.restore(target()))

        gate.complete(Unit)
        first.join()
        assertEquals(1, downloads.asks)
    }

    @Test
    fun cancellingLeavesTheRowCancelledAndWritesNoFile() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        downloads.gate = gate
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)

        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { runCatching { useCase(scope).restore(target()) } }
        assertTrue("the unconfined scope ran to the gate", downloads.insideDownload)
        job.cancelAndJoin()

        assertEquals(RestoreState.Cancelled, restores.stateOf(CHAT, MESSAGE))
        assertEquals(0, writer.stores)
        assertTrue(
            "the download layer owns stopping TDLib, so the use case has no cache file to release",
            downloads.released.isEmpty(),
        )
    }

    @Test
    fun reconcileAfterStartReleasesWhatThePreviousProcessAbandoned() = runBlocking<Unit> {
        restores.abandonedFiles = listOf(11, 12)

        useCase().reconcileAfterStart()

        assertEquals(listOf(11, 12), downloads.released)
        assertEquals(1, restores.reconciles)
    }

    @Test
    fun progressReachesTheRowAsTdLibCountsIt() = runBlocking<Unit> {
        dao.withItem(RESTORED_ID)
        media.indexed = localRow(RESTORED_ID)
        writer.result = StoredMedia.Ready(RESTORED_URI, RESTORED_ID, SIZE)
        hasher.digest = ContentDigest.Computed(LANDED_HASH, SIZE)
        downloads.result = OriginalDownload.Ready(fileId = 7, path = TDLIB_PATH, sizeBytes = SIZE)
        downloads.progress = listOf(1_000L, 2_500L)

        useCase().restore(target())

        assertEquals(
            "the last figure TDLib reported is what a bar shows when the transfer ends",
            listOf(1_000L, 2_500L),
            restores.progress,
        )
    }

    private fun target(
        manifestHash: String = "",
        sizeBytes: Long = SIZE,
        remoteFileId: String = "remote-original",
    ) = CloudRestoreTarget(
        chatId = CHAT,
        messageId = MESSAGE,
        remoteFileId = remoteFileId,
        mediaType = MediaType.Photo,
        mimeType = "image/jpeg",
        displayName = "IMG_0007.jpg",
        expectedSizeBytes = sizeBytes,
        manifestHash = manifestHash,
    )

    private fun localRow(id: Long) = Media(
        id = id,
        contentUri = RESTORED_URI,
        type = MediaType.Photo,
        mimeType = "image/jpeg",
        displayName = "IMG_0007.jpg",
        relativePath = "Pictures/LumoVault/",
        sizeBytes = SIZE,
        dateAddedSeconds = MODIFIED,
        dateModifiedSeconds = MODIFIED,
        width = 4,
        height = 3,
        durationMillis = null,
    )
}

/**
 * An in-memory job table that also records what the flow decided when, so a test can assert a row was
 * settled rather than assume it was. A live row refuses a second `take`, mirroring the SQL guard.
 */
private class FakeRestores : RestoreRepository {
    private val jobs = LinkedHashMap<Pair<Long, Long>, RestoreJob>()
    val progress = mutableListOf<Long>()
    var takes = 0
    var reconciles = 0
    var abandonedFiles: List<Int> = emptyList()
    var lastFailure: RestoreFailureKind? = null
    var completedMediaStoreId: Long? = null
    private var nextFileId = 1

    override suspend fun take(target: CloudRestoreTarget): RestoreJob? {
        takes += 1
        val key = target.chatId to target.messageId
        jobs[key]?.takeIf { it.state.isLive }?.let { return null }
        val job = RestoreJob(
            chatId = target.chatId,
            messageId = target.messageId,
            state = RestoreState.Pending,
            downloadedBytes = 0L,
            expectedSizeBytes = target.expectedSizeBytes,
            tdlibFileId = nextFileId++,
            mediaStoreId = 0L,
            failure = null,
        )
        jobs[key] = job
        return job
    }

    override suspend fun job(chatId: Long, messageId: Long): RestoreJob? = jobs[chatId to messageId]

    override suspend fun recordDownloadTarget(chatId: Long, messageId: Long, tdlibFileId: Int) {
        update(chatId, messageId) { it.copy(tdlibFileId = tdlibFileId) }
    }

    override suspend fun markDownloading(chatId: Long, messageId: Long, downloadedBytes: Long) {
        progress += downloadedBytes
        update(chatId, messageId) { it.copy(state = RestoreState.Downloading, downloadedBytes = downloadedBytes) }
    }

    override suspend fun markState(chatId: Long, messageId: Long, state: RestoreState) {
        update(chatId, messageId) { it.copy(state = state) }
    }

    override suspend fun complete(
        chatId: Long,
        messageId: Long,
        mediaStoreId: Long,
        contentHash: String,
        downloadedBytes: Long,
    ) {
        completedMediaStoreId = mediaStoreId
        update(chatId, messageId) {
            it.copy(
                state = RestoreState.Completed,
                mediaStoreId = mediaStoreId,
                downloadedBytes = downloadedBytes,
            )
        }
    }

    override suspend fun fail(chatId: Long, messageId: Long, failure: RestoreFailureKind) {
        lastFailure = failure
        update(chatId, messageId) { it.copy(state = RestoreState.Failed, failure = failure) }
    }

    override suspend fun cancel(chatId: Long, messageId: Long) {
        update(chatId, messageId) {
            it.copy(state = RestoreState.Cancelled, failure = RestoreFailureKind.Cancelled)
        }
    }

    override suspend fun reconcileInterrupted(): List<Int> {
        reconciles += 1
        val ids = abandonedFiles
        abandonedFiles = emptyList()
        return ids
    }

    fun stateOf(chatId: Long, messageId: Long): RestoreState? = jobs[chatId to messageId]?.state

    override fun observeForMessages(chatId: Long, messageIds: Collection<Long>): Flow<Map<Long, RestoreJob>> =
        flowOf(
            jobs.entries
                .filter { it.key.first == chatId && it.key.second in messageIds }
                .associate { (key, job) -> key.second to job },
        )

    override fun observeJob(chatId: Long, messageId: Long): Flow<RestoreJob?> =
        flowOf(jobs[chatId to messageId])

    override fun observeLive(limit: Int): Flow<List<RestoreJob>> =
        flowOf(jobs.values.filter { it.isLive }.take(limit))

    private fun update(chatId: Long, messageId: Long, transform: (RestoreJob) -> RestoreJob) {
        val key = chatId to messageId
        jobs[key]?.let { jobs[key] = transform(it) }
    }
}

private class FakeDownloads : TelegramOriginalRepository {
    var usable = true
    var result: OriginalDownload = OriginalDownload.Failed(RestoreFailure(RestoreFailureKind.SourceGone))
    var progress: List<Long> = emptyList()
    var gate: CompletableDeferred<Unit>? = null
    var asks = 0
    var insideDownload = false
    val released = mutableListOf<Int>()

    override val isUsable: Boolean
        get() = usable

    override suspend fun download(
        original: RemoteOriginal,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): OriginalDownload {
        asks += 1
        insideDownload = true
        progress.forEach { onProgress(DownloadProgress(it, SIZE)) }
        gate?.await()
        insideDownload = false
        return result
    }

    override suspend fun release(ready: OriginalDownload.Ready) {
        released += ready.fileId
    }
}

private class FakeWriter : RestoredMediaWriter {
    var roomAvailable = true
    var result: StoredMedia? = null
    var stores = 0
    var lastSource: RestorableSource? = null

    override fun hasRoomFor(sizeBytes: Long): Boolean = roomAvailable

    override suspend fun store(source: RestorableSource): StoredMedia {
        stores += 1
        lastSource = source
        return result ?: StoredMedia.Refused(RestoreFailure(RestoreFailureKind.SaveRejected))
    }
}

private class FakeHasher : MediaContentHasher {
    var digest: ContentDigest = ContentDigest.Computed(LANDED_HASH, SIZE)

    override suspend fun hash(contentUri: String): ContentDigest = digest

    override suspend fun hashFile(path: String): ContentDigest = digest
}

/**
 * The scanner as the restore sees it: [sync] is a counter and [local] is one row, because the only things
 * under test are whether a scan was asked for and whether the index had caught up afterwards.
 */
private class FakeMedia : MediaRepository {
    var indexed: Media? = null
    var syncs = 0

    override suspend fun local(mediaStoreId: Long): Media? = indexed?.takeIf { it.id == mediaStoreId }

    override suspend fun sync(): SyncResult {
        syncs += 1
        return SyncResult(indexed = if (indexed == null) 0 else 1, removed = 0)
    }

    override suspend fun clear() {
        indexed = null
    }

    override fun observeWindow(limit: Int): Flow<List<Media>> = flowOf(indexed?.let { listOf(it) } ?: emptyList())

    override fun observeCount(): Flow<Int> = flowOf(if (indexed == null) 0 else 1)

    override fun observeCountByType(): Flow<Map<MediaType, Int>> = flowOf(emptyMap())

    override fun observeFolders(): Flow<List<String>> = flowOf(listOf("Pictures/LumoVault/"))
}

private const val CHAT = 1_000L
private const val MESSAGE = 4_242L
private const val SIZE = 4_000L
private const val LOCAL_ID = 101L
private const val RESTORED_ID = 501L
private const val RESTORED_URI = "content://media/external/images/media/501"
private const val TDLIB_PATH = "/data/user/0/com.lumovault.app/files/tdlib_files/abc.jpg"
private const val MODIFIED = 1_790_000_000L

/** Two SHA-shaped values that are not interchangeable, which is the point of having both. */
private const val LANDED_HASH = "b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0"
private const val UPLOADED_HASH = "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1"

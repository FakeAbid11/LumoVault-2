package com.lumovault.app.data.local.backup

import com.lumovault.app.domain.backup.BackupItemState
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * An in-memory [BackupQueueDao], shared by the queue and the recognition tests.
 *
 * Mirrors the real queries rather than the real SQL: oldest-first claiming, the left joins that can return
 * a row with no media behind it, the state guards that decide whether a write lands at all, and grouping
 * for the summary. The guards are the part worth having: "recognition cannot settle a row a worker owns"
 * is a property of a `WHERE` clause, and a fake that ignored the clause would let a test pass while the
 * database did something else.
 *
 * Candidate ordering and limits follow the SQL too — newest added first, then highest id — so a bounded
 * pass is bounded here in the same way it is on a device.
 */
class FakeBackupQueueDao : BackupQueueDao {
    private val rows = LinkedHashMap<Long, BackupQueueEntity>()
    private val media = LinkedHashMap<Long, FakeMediaRow>()
    private val tick = MutableStateFlow(0)

    fun withMedia(vararg ids: Long) = put(ids, MediaType.Photo)

    fun withGif(vararg ids: Long) = put(ids, MediaType.Gif)

    fun withVideo(vararg ids: Long) = put(ids, MediaType.Video)

    /** One item, with the two figures the fast check reads. */
    fun withItem(id: Long, sizeBytes: Long = DEFAULT_SIZE, modifiedSeconds: Long = DEFAULT_MODIFIED) {
        media[id] = FakeMediaRow(type = MediaType.Photo, sizeBytes = sizeBytes, modifiedSeconds = modifiedSeconds)
        bump()
    }

    /** Replaces an index row without touching the record, which is what a re-scan of a changed file does. */
    fun changeMedia(id: Long, sizeBytes: Long, modifiedSeconds: Long) {
        val existing = media.getValue(id)
        media[id] = existing.copy(sizeBytes = sizeBytes, modifiedSeconds = modifiedSeconds)
        bump()
    }

    /** Moves only the timestamp, which is how an edit that kept the byte count still has to be caught. */
    fun touchMedia(id: Long, modifiedSeconds: Long) {
        media[id] = media.getValue(id).copy(modifiedSeconds = modifiedSeconds)
        bump()
    }

    fun dropMedia(id: Long) {
        media.remove(id)
        bump()
    }

    fun row(id: Long): BackupQueueEntity = rows.getValue(id)

    fun rowOrNull(id: Long): BackupQueueEntity? = rows[id]

    fun forceState(id: Long, state: UploadState) = forceRawState(id, state.storageKey)

    fun forceRawState(id: Long, state: String) {
        rows.getValue(id).let { rows[id] = it.copy(state = state) }
        bump()
    }

    override fun observeStatesFor(ids: Collection<Long>): Flow<List<BackupItemState>> = snapshots().map {
            current -> current.filter { it.mediaStoreId in ids }.map { BackupItemState(it.mediaStoreId, it.state) }
    }

    override fun observeCounts(): Flow<List<BackupStateCountRow>> = snapshots().map { current ->
        current.groupingBy { it.state }.eachCount()
            .map { (state, count) -> BackupStateCountRow(state, count) }
    }

    override suspend fun insertMissing(ids: Collection<Long>, queuedState: String, now: Long) {
        ids.forEach { id ->
            if (id in media && id !in rows) {
                rows[id] = BackupQueueEntity(
                    mediaStoreId = id,
                    state = queuedState,
                    queuedAt = now,
                    updatedAt = now,
                )
            }
        }
        bump()
    }

    override suspend fun countExisting(ids: Collection<Long>): Int =
        rows.values.count { it.mediaStoreId in ids }

    override suspend fun recordIdentity(
        id: Long,
        notBackedUpState: String,
        hash: String,
        sizeBytes: Long,
        modifiedSeconds: Long,
        hashedAt: Long,
    ) {
        // Identity columns and nothing else, exactly as the real statement restricts itself: a row a worker
        // owns keeps its state, its attempts and its failure through a hash landing.
        val existing = rows[id]
        rows[id] = existing?.copy(
            contentHash = hash,
            contentSizeBytes = sizeBytes,
            contentModifiedSeconds = modifiedSeconds,
            hashedAt = hashedAt,
            updatedAt = hashedAt,
        ) ?: BackupQueueEntity(
            mediaStoreId = id,
            state = notBackedUpState,
            contentHash = hash,
            contentSizeBytes = sizeBytes,
            contentModifiedSeconds = modifiedSeconds,
            hashedAt = hashedAt,
            updatedAt = hashedAt,
        )
        bump()
    }

    override suspend fun libraryIdentityCandidates(limit: Int): List<IdentityCandidateRow> =
        candidates(requireRecord = false, limit = limit)

    override suspend fun recordIdentityCandidates(limit: Int): List<IdentityCandidateRow> =
        candidates(requireRecord = true, limit = limit)

    override suspend fun adoptFromRemote(
        id: Long,
        chatId: Long,
        messageId: Long,
        backedUpState: String,
        fromStates: Collection<String>,
        now: Long,
    ): Int {
        val existing = rows[id] ?: return 0
        if (existing.state !in fromStates) return 0
        rows[id] = existing.copy(
            state = backedUpState,
            chatId = chatId,
            messageId = messageId,
            uploadedAt = now,
            updatedAt = now,
            failure = "",
            stagedPath = "",
        )
        bump()
        return 1
    }

    override suspend fun revokeAssociation(
        id: Long,
        toState: String,
        fromStates: Collection<String>,
        now: Long,
    ): Int {
        val existing = rows[id] ?: return 0
        if (existing.state !in fromStates) return 0
        rows[id] = existing.copy(
            state = toState,
            chatId = 0,
            messageId = 0,
            uploadedAt = 0,
            attempts = 0,
            failure = "",
            stagedPath = "",
            updatedAt = now,
        )
        bump()
        return 1
    }

    override suspend fun promoteRecognized(
        ids: Collection<Long>,
        queuedState: String,
        fromState: String,
        now: Long,
    ): Int {
        val matching = rows.values.filter { it.mediaStoreId in ids && it.state == fromState }
        matching.forEach {
            rows[it.mediaStoreId] = it.copy(state = queuedState, queuedAt = now, updatedAt = now)
        }
        bump()
        return matching.size
    }

    override suspend fun claimOldest(
        queuedState: String,
        preparingState: String,
        chatId: Long,
        now: Long,
    ): Int {
        val oldest = rows.values
            .filter { it.state == queuedState }
            .sortedWith(compareBy({ it.queuedAt }, { it.mediaStoreId }))
            .firstOrNull() ?: return 0

        rows[oldest.mediaStoreId] = oldest.copy(
            state = preparingState,
            chatId = chatId,
            updatedAt = now,
        )
        bump()
        return 1
    }

    override suspend fun newestIn(state: String): ClaimedBackupRow? {
        val row = rows.values.filter { it.state == state }.maxByOrNull { it.updatedAt } ?: return null
        val item = media[row.mediaStoreId]

        return ClaimedBackupRow(
            mediaStoreId = row.mediaStoreId,
            chatId = row.chatId,
            attempts = row.attempts,
            stagedPath = row.stagedPath,
            stateKey = row.state,
            failureKey = row.failure,
            contentHash = row.contentHash,
            messageId = row.messageId,
            contentSizeBytes = row.contentSizeBytes,
            contentModifiedSeconds = row.contentModifiedSeconds,
            mediaType = item?.mediaType,
            mimeType = item?.mimeType,
            contentUri = item?.contentUri(row.mediaStoreId),
            displayName = item?.displayName(row.mediaStoreId),
            sizeBytes = item?.sizeBytes,
            modifiedSeconds = item?.modifiedSeconds,
            width = item?.width,
            height = item?.height,
            durationMillis = item?.durationMillis,
        )
    }

    override suspend fun setState(id: Long, state: String, now: Long): Int = update(id) {
        it.copy(state = state, updatedAt = now)
    }

    override suspend fun setStagedPath(id: Long, path: String, now: Long): Int = update(id) {
        it.copy(stagedPath = path, updatedAt = now)
    }

    override suspend fun markSent(
        id: Long,
        chatId: Long,
        messageId: Long,
        sentState: String,
        now: Long,
    ): Int = update(id) {
        it.copy(
            state = sentState,
            chatId = chatId,
            messageId = messageId,
            uploadedAt = now,
            updatedAt = now,
            failure = "",
            stagedPath = "",
        )
    }

    override suspend fun settle(id: Long, state: String, attempts: Int, failure: String, now: Long): Int =
        update(id) {
            it.copy(state = state, attempts = attempts, failure = failure, updatedAt = now)
        }

    override suspend fun stagedPath(id: Long): String? = rows[id]?.stagedPath

    override suspend fun stateOf(id: Long): String? = rows[id]?.state

    override suspend fun moveAll(from: String, to: String, now: Long): Int {
        val matching = rows.values.filter { it.state == from }
        matching.forEach { rows[it.mediaStoreId] = it.copy(state = to, updatedAt = now) }
        bump()
        return matching.size
    }

    override suspend fun countIn(state: String): Int = rows.values.count { it.state == state }

    private fun put(ids: LongArray, type: MediaType) {
        ids.forEach { media[it] = FakeMediaRow(type = type, dateAddedSeconds = it) }
        bump()
    }

    private fun candidates(requireRecord: Boolean, limit: Int): List<IdentityCandidateRow> =
        media.entries
            .mapNotNull { (id, item) ->
                val record = rows[id]
                if (record == null) {
                    // No record at all: the frontier only when the caller asked for the whole library.
                    if (requireRecord) null else Candidate(item, id, null)
                } else {
                    val needsIdentity = record.hashedAt == 0L ||
                        item.sizeBytes != record.contentSizeBytes ||
                        item.modifiedSeconds != record.contentModifiedSeconds
                    if (needsIdentity) Candidate(item, id, record) else null
                }
            }
            .sortedWith(
                compareByDescending<Candidate> { it.item.dateAddedSeconds }
                    .thenByDescending { it.id },
            )
            .take(limit)
            .map { candidate ->
                val item = candidate.item
                val record = candidate.record
                IdentityCandidateRow(
                    mediaStoreId = candidate.id,
                    contentUri = item.contentUri(candidate.id),
                    mediaType = item.mediaType,
                    mimeType = item.mimeType,
                    displayName = item.displayName(candidate.id),
                    sizeBytes = item.sizeBytes,
                    modifiedSeconds = item.modifiedSeconds,
                    knownHash = record?.contentHash,
                    stateKey = record?.state,
                    chatId = record?.chatId,
                    messageId = record?.messageId,
                )
            }

    private data class Candidate(val item: FakeMediaRow, val id: Long, val record: BackupQueueEntity?)

    private suspend fun update(id: Long, transform: (BackupQueueEntity) -> BackupQueueEntity): Int {
        val existing = rows[id] ?: return 0
        rows[id] = transform(existing)
        bump()
        return 1
    }

    private fun snapshots(): Flow<List<BackupQueueEntity>> = tick.map { rows.values.toList() }

    private fun bump() {
        tick.value++
    }

    companion object {
        const val DEFAULT_SIZE = 1024L
        const val DEFAULT_MODIFIED = 1_790_000_000L
    }
}

/**
 * A row of the media index, as far as the queue's joins care. The id is passed to the derived fields
 * rather than stored, because in the real table the id is the key and the uri is built from it.
 */
data class FakeMediaRow(
    val type: MediaType = MediaType.Photo,
    val sizeBytes: Long = FakeBackupQueueDao.DEFAULT_SIZE,
    val modifiedSeconds: Long = FakeBackupQueueDao.DEFAULT_MODIFIED,
    val dateAddedSeconds: Long = FakeBackupQueueDao.DEFAULT_MODIFIED,
    val width: Int = 4,
    val height: Int = 3,
    val durationMillis: Long? = null,
) {
    val mediaType: String get() = type.storageKey
    val mimeType: String get() = when (type) {
        MediaType.Photo -> "image/jpeg"
        MediaType.Video -> "video/mp4"
        MediaType.Gif -> "image/gif"
    }

    fun contentUri(id: Long): String = when (type) {
        MediaType.Video -> "content://media/external/video/media/$id"
        else -> "content://media/external/images/media/$id"
    }

    fun displayName(id: Long): String = when (type) {
        MediaType.Photo -> "IMG_$id.jpg"
        MediaType.Video -> "clip_$id.mp4"
        MediaType.Gif -> "loop_$id.gif"
    }
}

/** A clock the queue tests advance by hand, so timestamps are asserted rather than raced. */
class QueueClock {
    var nowValue = 1L

    fun now(): Long = nowValue

    fun advance(by: Long) {
        nowValue += by
    }
}

package com.lumovault.app.data.local.backup

import androidx.room.Dao
import androidx.room.Query
import com.lumovault.app.domain.backup.BackupItemState
import kotlinx.coroutines.flow.Flow

/**
 * The queue's only persistent store.
 *
 * Every write here is a single statement, and the one that matters — handing an item to a worker — is
 * a conditional `UPDATE` rather than a select-then-write. Two workers overlapping by accident is not
 * a hypothetical in a system with both WorkManager retries and a manual trigger, and the cost of
 * getting it wrong is a duplicate photo in the user's channel.
 *
 * State strings are bind parameters rather than literals in the SQL so the enum stays the only place
 * a state is spelled.
 */
@Dao
interface BackupQueueDao {
    @Query(
        """
        SELECT media_store_id AS mediaStoreId, state AS stateKey
        FROM backup_queue WHERE media_store_id IN (:ids)
        """,
    )
    fun observeStatesFor(ids: Collection<Long>): Flow<List<BackupItemState>>

    @Query(
        """
        SELECT state AS stateKey, COUNT(*) AS itemCount
        FROM backup_queue GROUP BY state
        """,
    )
    fun observeCounts(): Flow<List<BackupStateCountRow>>

    /**
     * Enqueues ids that have no row yet, and only those that exist in the media index — a queue row
     * for an item the scanner never indexed could never be staged, so refusing it here is more honest
     * than failing it later. Tapping "Back Up" twice therefore changes nothing the second time.
     *
     * Returns nothing because Room will not give an `INSERT` a row count: the affected-row total is
     * what [countExisting] either side of this statement reports, and a queue that could not answer
     * "how many did you take" could not tell the user whether their tap did anything.
     */
    @Query(
        """
        INSERT INTO backup_queue (media_store_id, state, queued_at, updated_at)
        SELECT m.media_store_id, :queuedState, :now, :now FROM media m
        WHERE m.media_store_id IN (:ids)
          AND NOT EXISTS (
              SELECT 1 FROM backup_queue b WHERE b.media_store_id = m.media_store_id
          )
        """,
    )
    suspend fun insertMissing(ids: Collection<Long>, queuedState: String, now: Long)

    @Query("SELECT COUNT(*) FROM backup_queue WHERE media_store_id IN (:ids)")
    suspend fun countExisting(ids: Collection<Long>): Int

    /**
     * Records what one file *is*, and nothing else.
     *
     * This statement is the reason recognition can run alongside a worker without a lock: it writes the
     * identity columns and touches no state, no attempt count and no failure. A row in flight keeps
     * uploading while its hash lands; a row that finished keeps its message id. Phase 6 section 12 asks
     * for recognition not to corrupt active upload states, and the way to guarantee that is a write that
     * structurally cannot.
     *
     * Inserting here is how a never-queued item gets a record at all, and it arrives as
     * [com.lumovault.app.domain.backup.UploadState.NotBackedUp] — identity known, nothing stored remotely.
     * A *successful* hash and a *failed* one share this statement, distinguished by whether `hash` is
     * empty and by `hashedAt` being non-zero either way: recording the attempt is what stops an
     * unreadable file from spending the hashing budget on every subsequent pass.
     */
    @Query(
        """
        INSERT INTO backup_queue (
            media_store_id, state, content_hash, content_size_bytes, content_modified_seconds,
            hashed_at, updated_at
        ) VALUES (:id, :notBackedUpState, :hash, :sizeBytes, :modifiedSeconds, :hashedAt, :hashedAt)
        ON CONFLICT(media_store_id) DO UPDATE SET
            content_hash = :hash, content_size_bytes = :sizeBytes,
            content_modified_seconds = :modifiedSeconds, hashed_at = :hashedAt, updated_at = :hashedAt
        """,
    )
    suspend fun recordIdentity(
        id: Long,
        notBackedUpState: String,
        hash: String,
        sizeBytes: Long,
        modifiedSeconds: Long,
        hashedAt: Long,
    )

    /**
     * The library's unrecognised frontier: items with no record, never-hashed items, and items whose size
     * or modification time moved since their hash was taken.
     *
     * The join is a `LEFT JOIN` from `media` so that an item nobody ever queued is still findable — that
     * is the whole reinstall-recovery case, where the local table is empty and the content has to be
     * identified from scratch.
     *
     * Ordered newest-first because that is where a user's camera roll is: after a reinstall, recognising
     * yesterday's photos matters more than reaching 2019, and a bounded pass can stop anywhere in this
     * order without leaving a hole.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri,
               m.size_bytes AS sizeBytes, m.date_modified_seconds AS modifiedSeconds,
               m.media_type AS mediaType, m.display_name AS displayName, m.mime_type AS mimeType,
               b.content_hash AS knownHash, b.state AS stateKey, b.chat_id AS chatId,
               b.message_id AS messageId
        FROM media m
        LEFT JOIN backup_queue b ON b.media_store_id = m.media_store_id
        WHERE b.media_store_id IS NULL OR b.hashed_at = 0
           OR m.size_bytes <> b.content_size_bytes
           OR m.date_modified_seconds <> b.content_modified_seconds
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    suspend fun libraryIdentityCandidates(limit: Int): List<IdentityCandidateRow>

    /**
     * The same frontier restricted to items that already have a record, which is the set whose identity
     * can be *wrong* rather than merely unknown: queued items that need a hash before they are sent, and
     * completed backups whose file has since been edited or replaced.
     *
     * Cheap enough to run on every pass, unlike the library-wide one, because it is bounded by the number
     * of rows the app has already decided something about rather than by the size of the library.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri,
               m.size_bytes AS sizeBytes, m.date_modified_seconds AS modifiedSeconds,
               m.media_type AS mediaType, m.display_name AS displayName, m.mime_type AS mimeType,
               b.content_hash AS knownHash, b.state AS stateKey, b.chat_id AS chatId,
               b.message_id AS messageId
        FROM media m
        JOIN backup_queue b ON b.media_store_id = m.media_store_id
        WHERE b.hashed_at = 0
           OR m.size_bytes <> b.content_size_bytes
           OR m.date_modified_seconds <> b.content_modified_seconds
        ORDER BY m.date_added_seconds DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    suspend fun recordIdentityCandidates(limit: Int): List<IdentityCandidateRow>

    /**
     * Points a record at a remote message that already holds its content.
     *
     * The `state IN (:fromStates)` guard is the entire safety of this statement, so it is worth being
     * exact about what it excludes. A `preparing` or `uploading` row belongs to a worker mid-send:
     * rewriting it from a scan would either lose the message id that send is about to produce or, worse,
     * mark a row backed up while its own bytes are still going out a second time. A `queued` row *is*
     * allowed, and that is the point — the item is about to be sent, recognition found it already stored,
     * and the correct answer to that is to drop it from the queue with a ✓ rather than upload a duplicate.
     *
     * Returns the row count so the caller learns whether it adopted anything rather than assumed it did.
     */
    @Query(
        """
        UPDATE backup_queue
        SET state = :backedUpState, chat_id = :chatId, message_id = :messageId, uploaded_at = :now,
            updated_at = :now, failure = '', staged_path = ''
        WHERE media_store_id = :id AND state IN (:fromStates)
        """,
    )
    suspend fun adoptFromRemote(
        id: Long,
        chatId: Long,
        messageId: Long,
        backedUpState: String,
        fromStates: Collection<String>,
        now: Long,
    ): Int

    /**
     * Detaches a record from a backup that no longer describes it.
     *
     * The file behind a completed backup became different content, so the message holding the old bytes
     * is not this item's home any more — but the message itself is left completely alone, because
     * deleting a user's stored photo is not a recognition scan's business (PRD section 72's rule, and
     * phase 6 section 10's instruction not to delete).
     *
     * Restricted to the states that actually assert a stored home. Demoting a `queued` row from here would
     * silently drop work the user asked for, and a `preparing` or `uploading` row belongs to the worker
     * that claimed it — so the caller passes [com.lumovault.app.domain.backup.UploadState.BackedUp] and
     * nothing else.
     */
    @Query(
        """
        UPDATE backup_queue
        SET state = :toState, chat_id = 0, message_id = 0, uploaded_at = 0, attempts = 0,
            failure = '', staged_path = '', updated_at = :now
        WHERE media_store_id = :id AND state IN (:fromStates)
        """,
    )
    suspend fun revokeAssociation(
        id: Long,
        toState: String,
        fromStates: Collection<String>,
        now: Long,
    ): Int

    /**
     * Moves recognised items into the queue when the user asks for them.
     *
     * Restricted to [com.lumovault.app.domain.backup.UploadState.NotBackedUp] on purpose. An item already
     * failed is retried through the retry affordance, and an item already queued, in flight or backed up
     * is left exactly as it is — a second tap on "Back Up" must not restart a send that is running.
     */
    @Query(
        """
        UPDATE backup_queue SET state = :queuedState, queued_at = :now, updated_at = :now
        WHERE media_store_id IN (:ids) AND state = :fromState
        """,
    )
    suspend fun promoteRecognized(
        ids: Collection<Long>,
        queuedState: String,
        fromState: String,
        now: Long,
    ): Int

    /**
     * Hands the oldest queued item to a worker in one statement.
     *
     * The `state = :queuedState` outside the subquery is what makes it safe: a second caller whose
     * subquery resolved the same row before the first committed will then match nothing, so exactly
     * one caller gets a row count of 1.
     */
    @Query(
        """
        UPDATE backup_queue
        SET state = :preparingState, chat_id = :chatId, updated_at = :now
        WHERE media_store_id = (
            SELECT media_store_id FROM backup_queue
            WHERE state = :queuedState ORDER BY queued_at ASC, media_store_id ASC LIMIT 1
        ) AND state = :queuedState
        """,
    )
    suspend fun claimOldest(
        queuedState: String,
        preparingState: String,
        chatId: Long,
        now: Long,
    ): Int

    /**
     * The row a claim just produced, joined to the media it refers to.
     *
     * Newest first: [claimOldest] stamps `updated_at` as it takes the row, so the most recently
     * modified `preparing` row is the one this worker won. Reconciliation clears any leftovers before
     * a worker runs, so this is the tie-break that keeps a recovered row from being read as a new one.
     *
     * A `LEFT JOIN` on purpose: a media row can be pruned by a later scan while its queue row is
     * pending, and the nullable columns are how the caller learns that the item it was told to back up
     * no longer exists, rather than reading a zero-size phantom.
     */
    @Query(
        """
        SELECT b.media_store_id AS mediaStoreId, b.chat_id AS chatId, b.attempts AS attempts,
               b.staged_path AS stagedPath, b.state AS stateKey, b.failure AS failureKey,
               b.content_hash AS contentHash, b.message_id AS messageId,
               b.content_size_bytes AS contentSizeBytes,
               b.content_modified_seconds AS contentModifiedSeconds,
               m.media_type AS mediaType, m.mime_type AS mimeType, m.content_uri AS contentUri,
               m.display_name AS displayName, m.size_bytes AS sizeBytes,
               m.date_modified_seconds AS modifiedSeconds,
               m.width AS width, m.height AS height, m.duration_millis AS durationMillis
        FROM backup_queue b
        LEFT JOIN media m ON m.media_store_id = b.media_store_id
        WHERE b.state = :state ORDER BY b.updated_at DESC LIMIT 1
        """,
    )
    suspend fun newestIn(state: String): ClaimedBackupRow?

    @Query("UPDATE backup_queue SET state = :state, updated_at = :now WHERE media_store_id = :id")
    suspend fun setState(id: Long, state: String, now: Long): Int

    @Query("UPDATE backup_queue SET staged_path = :path, updated_at = :now WHERE media_store_id = :id")
    suspend fun setStagedPath(id: Long, path: String, now: Long): Int

    /**
     * The only write that records a created message, and it sets the chat from the outcome rather
     * than leaving the claim's guess in place — the row should say where the bytes actually landed.
     */
    @Query(
        """
        UPDATE backup_queue
        SET state = :sentState, chat_id = :chatId, message_id = :messageId, uploaded_at = :now,
            updated_at = :now, failure = '', staged_path = ''
        WHERE media_store_id = :id
        """,
    )
    suspend fun markSent(id: Long, chatId: Long, messageId: Long, sentState: String, now: Long): Int

    /** Failure and retry share one write because both decide the row's state and its attempt count. */
    @Query(
        """
        UPDATE backup_queue
        SET state = :state, attempts = :attempts, failure = :failure, updated_at = :now
        WHERE media_store_id = :id
        """,
    )
    suspend fun settle(id: Long, state: String, attempts: Int, failure: String, now: Long): Int

    @Query("SELECT staged_path FROM backup_queue WHERE media_store_id = :id")
    suspend fun stagedPath(id: Long): String?

    /**
     * One row's state, read rather than assumed.
     *
     * Every state change goes through this so the repository can refuse a transition the stored row
     * does not support — the alternative is trusting the caller to remember where it left the item,
     * which is precisely the kind of assumption that puts a ✓ on a photo nothing uploaded.
     */
    @Query("SELECT state FROM backup_queue WHERE media_store_id = :id")
    suspend fun stateOf(id: Long): String?

    @Query("UPDATE backup_queue SET state = :to, updated_at = :now WHERE state = :from")
    suspend fun moveAll(from: String, to: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM backup_queue WHERE state = :state")
    suspend fun countIn(state: String): Int

    /**
     * Which of [hashes] the device's own media files are backed by, right now.
     *
     * The join to `media` is the whole point. A backup record outlives the file it was made from — that
     * is PRD section 72's cloud-only state, and the record must survive — so a claimed hash on its own
     * says only "this content was seen here". Joined, it says the original is still on the device, which
     * is the difference the Cloud screen draws between *local + cloud* and *cloud only*.
     */
    @Query(
        """
        SELECT b.content_hash FROM backup_queue b
        JOIN media m ON m.media_store_id = b.media_store_id
        WHERE b.content_hash IN (:hashes)
        """,
    )
    suspend fun hashesStillOnDevice(hashes: Collection<String>): List<String>
}

/** The grouped-count projection. Mapped into [com.lumovault.app.domain.backup.BackupQueueSummary] in
 * the repository, so the arithmetic that the progress line shows is testable without a database. */
data class BackupStateCountRow(
    val stateKey: String,
    val itemCount: Int,
)

/** Projection of [BackupQueueDao.newestIn]. Everything from `media` is nullable because the join is a
 * left join, and a null there means the item has left the index. */
data class ClaimedBackupRow(
    val mediaStoreId: Long,
    val chatId: Long,
    val attempts: Int,
    val stagedPath: String,
    val stateKey: String,
    val failureKey: String,
    /** Empty until recognition has hashed this item, which the upload path must not do without. */
    val contentHash: String,
    /** Non-zero on a row that was already sent once, which is what a re-claim after a retry keeps. */
    val messageId: Long,
    /** The size and modification time [contentHash] was taken against — the fast check's other half. */
    val contentSizeBytes: Long,
    val contentModifiedSeconds: Long,
    val mediaType: String?,
    val mimeType: String?,
    val contentUri: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    /** MediaStore's modification time right now, which is what a manifest records a hash against. */
    val modifiedSeconds: Long?,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
)

/**
 * One item whose identity is missing or doubtful, and what the record already claims about it.
 *
 * The `b.*` fields are nullable because the driving table is `media`: an item the app has never
 * recognised has no record yet, and that is a normal row here rather than a partial one.
 */
data class IdentityCandidateRow(
    val mediaStoreId: Long,
    val contentUri: String,
    val mediaType: String,
    val mimeType: String,
    val displayName: String,
    /** MediaStore's current figure, which is the half of the fast check that costs nothing. */
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val knownHash: String?,
    val stateKey: String?,
    val chatId: Long?,
    val messageId: Long?,
)

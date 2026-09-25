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
    suspend fun insertMissing(ids: Collection<Long>, queuedState: String, now: Long): Int

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
               m.media_type AS mediaType, m.mime_type AS mimeType, m.content_uri AS contentUri,
               m.display_name AS displayName, m.size_bytes AS sizeBytes,
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
    val mediaType: String?,
    val mimeType: String?,
    val contentUri: String?,
    val displayName: String?,
    val sizeBytes: Long?,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
)

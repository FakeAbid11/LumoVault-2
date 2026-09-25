package com.lumovault.app.data.local.restore

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.lumovault.app.domain.restore.RestoreState
import kotlinx.coroutines.flow.Flow

/**
 * The restore table: one row per download the user asked for.
 *
 * Two shapes here are worth noticing before changing anything.
 *
 * Every write is keyed by the *cloud* pair, because that is all a restore knows until it finishes — there
 * is no local row to point at yet. So is the read the Cloud screen does, which is why it asks for a window
 * of message ids rather than "everything".
 *
 * A request is two statements rather than one upsert, and that is deliberate: `@Upsert` is
 * `INSERT OR REPLACE`, which deletes and re-inserts the row, and this row is the thing a running transfer
 * is writing progress into. So [insertIgnoring] adds a row or reports that one exists, and [restart] moves
 * a *finished* row back to pending under a state list the caller cannot widen. A row that is mid-flight is
 * left exactly as it is, and the caller learns that from the row count instead of starting a second
 * transfer for the same message.
 */
@Dao
interface MediaRestoreDao {

    @Query(
        """
        SELECT * FROM media_restore
        WHERE chat_id = :chatId AND message_id IN (:messageIds)
        """,
    )
    fun observeForMessages(chatId: Long, messageIds: Collection<Long>): Flow<List<MediaRestoreEntity>>

    @Query("SELECT * FROM media_restore WHERE chat_id = :chatId AND message_id = :messageId")
    fun observeJob(chatId: Long, messageId: Long): Flow<MediaRestoreEntity?>

    @Query("SELECT * FROM media_restore WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun job(chatId: Long, messageId: Long): MediaRestoreEntity?

    /** Rows in a live state, newest request first — what a screen shows without being told which items. */
    @Query(
        """
        SELECT * FROM media_restore
        WHERE state IN (:liveStates)
        ORDER BY requested_at DESC, message_id DESC
        LIMIT :limit
        """,
    )
    fun observeLive(liveStates: List<String>, limit: Int): Flow<List<MediaRestoreEntity>>

    @Query("SELECT * FROM media_restore WHERE state IN (:liveStates)")
    suspend fun liveRows(liveStates: List<String>): List<MediaRestoreEntity>

    /**
     * Adds a request, or changes nothing when one already exists for that message.
     *
     * `INSERT OR IGNORE` returns −1 when the row was already there, which is the caller's signal to look at
     * the existing row rather than assume a fresh one: a tap on something that finished yesterday has to be
     * distinguishable from a tap on something downloading now, and only the stored state can say which.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(entity: MediaRestoreEntity): Long

    /**
     * Puts a finished row back at the start, clearing everything the previous run learned.
     *
     * [restartableStates] is passed in rather than written as `NOT IN (live)`, so which states a restart
     * may overwrite is decided by the repository that has to reason about them — and the row count is the
     * answer to "did my tap do anything", which is the one question a double tap has to be able to ask.
     */
    @Query(
        """
        UPDATE media_restore
        SET state = :state, failure = '', downloaded_bytes = 0, media_store_id = 0, content_hash = '',
            tdlib_file_id = 0, updated_at = :updatedAt
        WHERE chat_id = :chatId AND message_id = :messageId AND state IN (:restartableStates)
        """,
    )
    suspend fun restart(
        chatId: Long,
        messageId: Long,
        restartableStates: List<String>,
        state: String,
        updatedAt: Long,
    ): Int

    /**
     * Records which TDLib file this transfer is, as soon as it has an id.
     *
     * Before the first progress callback, because the id is the only handle on the bytes once they land —
     * and a process killed thirty seconds into a large video leaves a cache file that nothing can release
     * without it.
     */
    @Query("UPDATE media_restore SET tdlib_file_id = :fileId, updated_at = :updatedAt WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun recordDownloadTarget(chatId: Long, messageId: Long, fileId: Int, updatedAt: Long): Int

    @Query(
        """
        UPDATE media_restore
        SET state = :state, downloaded_bytes = :downloadedBytes, updated_at = :updatedAt
        WHERE chat_id = :chatId AND message_id = :messageId
        """,
    )
    suspend fun advance(chatId: Long, messageId: Long, state: String, downloadedBytes: Long, updatedAt: Long): Int

    /**
     * Settles a restore that made it as far as MediaStore.
     *
     * [contentHash] is the hash of the bytes that landed, which is what lets the queue record this
     * content as stored without claiming it matches what the device once sent.
     */
    @Query(
        """
        UPDATE media_restore
        SET state = :state, media_store_id = :mediaStoreId, content_hash = :contentHash, failure = '',
            downloaded_bytes = :downloadedBytes, updated_at = :updatedAt
        WHERE chat_id = :chatId AND message_id = :messageId
        """,
    )
    suspend fun complete(
        chatId: Long,
        messageId: Long,
        state: String,
        mediaStoreId: Long,
        contentHash: String,
        downloadedBytes: Long,
        updatedAt: Long,
    ): Int

    /** A restore that stopped. The temp path is kept, because the caller removes the file before calling. */
    @Query(
        """
        UPDATE media_restore
        SET state = :state, failure = :failure, updated_at = :updatedAt
        WHERE chat_id = :chatId AND message_id = :messageId
        """,
    )
    suspend fun fail(chatId: Long, messageId: Long, state: String, failure: String, updatedAt: Long): Int

    /**
     * Rows a killed process left mid-flight, moved to [RestoreState.Failed] under one reason.
     *
     * Their `temp_path` is deliberately untouched by the statement, so the caller can still see which
     * files to delete; the sweep of the directory itself happens in the repository that owns the path, not
     * in SQL.
     */
    @Query(
        """
        UPDATE media_restore
        SET state = :toState, failure = :failure, updated_at = :updatedAt
        WHERE state IN (:liveStates)
        """,
    )
    suspend fun reconcileInterrupted(
        liveStates: List<String>,
        toState: String,
        failure: String,
        updatedAt: Long,
    ): Int
}

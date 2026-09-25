package com.lumovault.app.data.local.cloud

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CloudMediaDao {
    /**
     * Windowed like the local timeline: a channel can hold tens of thousands of messages, and the
     * Cloud screen must not load them all to draw a screen.
     */
    @Query("SELECT * FROM cloud_media ORDER BY date_seconds DESC, message_id DESC LIMIT :limit")
    fun observeWindow(limit: Int): Flow<List<CloudMediaEntity>>

    @Query("SELECT COUNT(*) FROM cloud_media")
    fun observeCount(): Flow<Int>

    @Query("SELECT media_type AS mediaType, COUNT(*) AS itemCount FROM cloud_media GROUP BY media_type")
    fun observeTypeCounts(): Flow<List<CloudTypeCountRow>>

    @Query("SELECT COUNT(*) FROM cloud_media")
    suspend fun currentCount(): Int

    /**
     * The remote message that already holds these exact bytes, or null when nothing does.
     *
     * This is the query Phase 6 turns on: a SHA-256 of a local file in, a Telegram message id out. It is
     * indexed ([CloudMediaEntity.contentHash]) because it runs once per item the app hashes, and because
     * on a ten-thousand-message channel the unindexed alternative is a scan per photo.
     *
     * Oldest message wins when two carry the same hash, which is a real state rather than a bug: two
     * phones can back up the same file, or an interrupted send can be retried. Any one of them is a
     * correct home for the content, and picking deterministically means the same local file resolves to
     * the same message on every pass instead of flickering between them.
     */
    @Query(
        """
        SELECT message_id AS messageId, chat_id AS chatId FROM cloud_media
        WHERE content_hash = :hash ORDER BY message_id ASC LIMIT 1
        """,
    )
    suspend fun backupFor(hash: String): RemoteBackupRow?

    /**
     * How many remote manifests no local record claims.
     *
     * This is the number that decides whether hashing anything is worth doing: recognition can only match
     * content against a remote index that still has unmatched entries in it, so a library of 100,000
     * files whose backups are all recognised costs zero file reads. When the number is 1,000 — a
     * reinstall — the same figure bounds the work that matters.
     *
     * The join into `backup_queue` is deliberate and read-only. "Unrecognised" is a fact about the pair of
     * indexes rather than about either alone, and the alternative was the same statement living on the
     * other table, reaching into this one.
     */
    @Query(
        """
        SELECT COUNT(*) FROM cloud_media c
        WHERE c.content_hash <> ''
          AND NOT EXISTS (SELECT 1 FROM backup_queue b WHERE b.content_hash = c.content_hash)
        """,
    )
    suspend fun unrecognizedManifestCount(): Int

    @Upsert
    suspend fun upsertAll(items: List<CloudMediaEntity>)

    @Query("DELETE FROM cloud_media WHERE last_seen_scan_id < :scanId")
    suspend fun pruneBefore(scanId: Long): Int

    /**
     * Only for an account switch, where the whole index belongs to a different user. A failed or
     * partial scan never calls this; pruning by scan id does.
     */
    @Query("DELETE FROM cloud_media")
    suspend fun clear()
}

data class CloudTypeCountRow(
    val mediaType: String,
    val itemCount: Int,
)

/** Where the channel says these exact bytes already live. Mapped to the domain in the repository. */
data class RemoteBackupRow(
    val messageId: Long,
    val chatId: Long,
)

@Dao
interface CloudChannelDao {
    // No Kotlin default arguments: Room's generated implementation of a defaulted parameter is a
    // known rough edge, and the one caller can pass the singleton id itself.
    @Query("SELECT * FROM cloud_channel WHERE id = :id LIMIT 1")
    suspend fun row(id: Int): CloudChannelEntity?

    @Query("SELECT * FROM cloud_channel WHERE id = :id LIMIT 1")
    fun observeRow(id: Int): Flow<CloudChannelEntity?>

    @Upsert
    suspend fun upsert(row: CloudChannelEntity)

    @Query("DELETE FROM cloud_channel")
    suspend fun clear()
}

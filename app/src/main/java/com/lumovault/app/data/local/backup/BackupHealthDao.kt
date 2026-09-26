package com.lumovault.app.data.local.backup

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * The two figures Backup Health and Diagnostics need that no other table can answer, and only those.
 *
 * Everything else on those screens is already a query someone wrote for a reason — the queue's counts by
 * state, the media index's total, Free Up Space's totals — and repeating a number in a second statement is how
 * two screens end up disagreeing about the same library.
 *
 * Both are aggregates. Neither loads a row.
 */
@Dao
interface BackupHealthDao {

    /**
     * Remote records with nothing on this device behind them.
     *
     * "Nothing on the device" is asked the same way the rest of the app asks it — a queue row that names this
     * message, for an item the index still holds — rather than by filename or size. A `LEFT JOIN` on
     * `media` alone would call a freed-up photo "cloud only" the moment its row was deleted while its backup
     * record survived, which is the state Free Up Space is supposed to produce.
     */
    @Query(
        """
        SELECT COUNT(*) FROM cloud_media c
        WHERE NOT EXISTS (
            SELECT 1 FROM backup_queue b
            JOIN media m ON m.media_store_id = b.media_store_id
            WHERE b.chat_id = c.chat_id AND b.message_id = c.message_id
        )
        """,
    )
    fun observeCloudOnlyCount(): Flow<Int>

    /**
     * When the most recent backup settled, in epoch seconds, or null when none ever has.
     *
     * `MAX(uploaded_at)` over the completed rows rather than a column nobody keeps: `uploaded_at` is written
     * by the two paths that can honestly claim a stored home — a send Telegram confirmed, and a manifest this
     * app matched — and is zeroed when an association is revoked, so the newest of them is the last backup
     * in the only sense this database can define.
     */
    @Query("SELECT MAX(uploaded_at) FROM backup_queue WHERE state = :backedUpState AND uploaded_at <> 0")
    fun observeLastBackupSeconds(backedUpState: String): Flow<Long?>
}

package com.lumovault.app.domain.repository

import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.telegram.CloudAssociation
import kotlinx.coroutines.flow.Flow

/**
 * The local half of the cloud: an index of what Telegram holds, which is what lets LumoVault behave
 * like a photo library instead of a message browser (PRD section 15).
 *
 * It is a separate repository from [MediaRepository] because the two have different lifetimes — this
 * one is cleared when the Telegram account changes, and never when the device's own files change.
 */
interface CloudIndexRepository {
    fun observeWindow(limit: Int): Flow<List<CloudMedia>>

    fun observeCount(): Flow<Int>

    fun observeTypeCounts(): Flow<List<CloudTypeCount>>

    suspend fun currentCount(): Int

    /** One page of scan results, written in a single transaction. */
    suspend fun savePage(chatId: Long, scanId: Long, items: List<CloudMedia>)

    /**
     * Closes a scan: optionally drops remote rows the walk did not see, moves the resume cursor and
     * stamps the sync time. Returns how many rows were removed.
     *
     * [prune] is false when a scan resumed from a cursor rather than starting at the newest message:
     * everything above that cursor belongs to an earlier generation and would look missing to a
     * deletion that assumed a complete walk.
     */
    suspend fun finishScan(scanId: Long, association: CloudAssociation, prune: Boolean): Int

    suspend fun association(): CloudAssociation?

    /** Remembers the adopted channel. Existing index rows are kept: a rescan reconciles them. */
    suspend fun saveAssociation(association: CloudAssociation)

    /**
     * Adopts a channel belonging to a different Telegram account, which means the old index is
     * someone else's library and must not survive alongside the new one (PRD section 73).
     */
    suspend fun replaceAssociation(association: CloudAssociation)

    suspend fun dropAssociation()
}

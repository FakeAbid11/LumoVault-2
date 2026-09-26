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

    /**
     * The remote message that already holds these exact bytes, or null when the index has none.
     *
     * This is the whole of duplicate prevention: one SHA-256 in, a Telegram message id out. A blank
     * [contentHash] answers null without touching the database, because an empty hash is what every
     * message without a manifest stores, and matching on it would tie every unrecognised file in the
     * library to an arbitrary photo in the channel.
     */
    suspend fun remoteBackupFor(contentHash: String): RemoteBackup?

    /**
     * How much of the remote index no local record claims.
     *
     * Recognition reads this before it reads a single file: at zero, hashing anything cannot find a match
     * it does not already have, and the difference between that and a reinstall — where the figure is the
     * size of the library — is the difference between an idle pass and the only pass where computing
     * hashes is the point.
     */
    suspend fun unrecognizedRemoteCount(): Int

    /** Remembers the adopted channel. Existing index rows are kept: a rescan reconciles them. */
    suspend fun saveAssociation(association: CloudAssociation)

    /**
     * Adopts a channel belonging to a different Telegram account, which means the old index is
     * someone else's library and must not survive alongside the new one (PRD section 73).
     */
    suspend fun replaceAssociation(association: CloudAssociation)

    suspend fun dropAssociation()
}

/**
 * A remote home for local content: the message in the storage channel whose manifest declares this exact
 * hash.
 *
 * Deliberately only an address. Recognition needs to know *where* the content already is so it can record
 * it and skip the upload; everything else about the message — its size, its preview, its caption — is
 * already in the index and would only tempt a second read of something that should not be downloaded.
 */
data class RemoteBackup(
    val chatId: Long,
    val messageId: Long,
)

package com.lumovault.app.data.local.backup

import androidx.room.Dao
import androidx.room.Query
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

/** The headline figures: how many, and how much. Aggregate in SQL, so a library is never loaded to count it. */
data class FreeUpSpaceTotalRow(val itemCount: Int, val totalBytes: Long)

/**
 * The three statements Free Up Space needs, and the one rule they all share.
 *
 * An item is offered only when its backup record asserts a stored home — `backed_up`, a chat, a message, a
 * content hash — *and* the cloud index still holds that message, *and* the file is still on the device, *and*
 * it is not sitting in Trash. The join to `cloud_media` is the part that cannot be dropped: a queue row
 * pointing at a message that is no longer in the index is a claim nobody is still standing behind, and the
 * whole feature is about not deleting a file on a claim.
 *
 * Counts are `SUM`/`COUNT` in the database rather than a list walked in Compose, which is PRD section 66's
 * requirement in the one place this phase could satisfy it in a single statement.
 */
@Dao
interface FreeUpSpaceDao {

    @Query(
        """
        SELECT COUNT(*) AS itemCount, COALESCE(SUM(m.size_bytes), 0) AS totalBytes
        FROM media m
        JOIN backup_queue b ON b.media_store_id = m.media_store_id
        JOIN cloud_media c ON c.chat_id = b.chat_id AND c.message_id = b.message_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE b.state = :backedUpState AND b.chat_id <> 0 AND b.message_id <> 0 AND b.content_hash <> ''
          AND COALESCE(o.trashed_at, 0) = 0
        """,
    )
    fun observeTotals(backedUpState: String): Flow<FreeUpSpaceTotalRow>

    /**
     * Largest first.
     *
     * Ordering by bytes rather than by date is a deliberate product choice: the list is a review of what the
     * user is about to lose locally, and the two-hundred-megabyte video is the item they need to see at the
     * top of it.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri,
               m.display_name AS displayName, m.size_bytes AS sizeBytes, m.media_type AS mediaType
        FROM media m
        JOIN backup_queue b ON b.media_store_id = m.media_store_id
        JOIN cloud_media c ON c.chat_id = b.chat_id AND c.message_id = b.message_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE b.state = :backedUpState AND b.chat_id <> 0 AND b.message_id <> 0 AND b.content_hash <> ''
          AND COALESCE(o.trashed_at, 0) = 0
        ORDER BY m.size_bytes DESC, m.media_store_id DESC
        LIMIT :limit
        """,
    )
    suspend fun candidates(backedUpState: String, limit: Int): List<FreeUpSpaceCandidateRow>

    /**
     * Everything the per-item re-check needs, for ids the user selected — with the guards expressed as
     * columns rather than as a `WHERE`, because the phase asks for skipped items to be *explained*, and a
     * filtered-out row cannot say what was wrong with it.
     *
     * The three joins are all `LEFT` so an item that lost its queue record, its cloud message, or its index
     * row still comes back to be refused by name.
     */
    @Query(
        """
        SELECT m.media_store_id AS mediaStoreId, m.content_uri AS contentUri,
               m.display_name AS displayName, m.size_bytes AS sizeBytes, m.media_type AS mediaType,
               b.state AS queueState, b.chat_id AS queueChatId, b.message_id AS queueMessageId,
               b.content_hash AS queueHash, c.message_id AS cloudMessageId,
               COALESCE(o.trashed_at, 0) AS trashedAt
        FROM media m
        LEFT JOIN backup_queue b ON b.media_store_id = m.media_store_id
        LEFT JOIN cloud_media c ON c.chat_id = b.chat_id AND c.message_id = b.message_id
        LEFT JOIN media_organization o ON o.media_store_id = m.media_store_id
        WHERE m.media_store_id IN (:ids)
        """,
    )
    suspend fun eligibilityFor(ids: Collection<Long>): List<EligibilityRow>
}

/** A row of [FreeUpSpaceDao.candidates]. Field names match the columns so Room needs no aliases. */
data class FreeUpSpaceCandidateRow(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mediaType: String,
) {
    fun toCandidate() = FreeUpSpaceCandidate(
        mediaStoreId = mediaStoreId,
        contentUri = contentUri,
        displayName = displayName,
        sizeBytes = sizeBytes,
        mediaType = MediaType.fromStorageKey(mediaType),
    )
}

/** A row of [FreeUpSpaceDao.eligibilityFor]. Nulls here are the refusals, not missing data. */
data class EligibilityRow(
    val mediaStoreId: Long,
    val contentUri: String,
    val displayName: String,
    val sizeBytes: Long,
    val mediaType: String,
    val queueState: String?,
    val queueChatId: Long?,
    val queueMessageId: Long?,
    val queueHash: String?,
    val cloudMessageId: Long?,
    val trashedAt: Long,
)

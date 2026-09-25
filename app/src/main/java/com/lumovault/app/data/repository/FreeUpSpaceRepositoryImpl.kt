package com.lumovault.app.data.repository

import com.lumovault.app.data.local.backup.EligibilityRow
import com.lumovault.app.data.local.backup.FreeUpSpaceDao
import com.lumovault.app.domain.backup.UploadState
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.repository.FreeUpSpaceRepository
import com.lumovault.app.domain.restore.EligibilityCheck
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.restore.FreeUpSpaceEligibility
import com.lumovault.app.domain.restore.FreeUpSpacePlan
import com.lumovault.app.domain.restore.ItemEvidence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Reads the eligibility rule out of the database, then applies it in [FreeUpSpaceEligibility].
 *
 * The split is the one the phase asks for: which columns may assert a stored home is SQL's question, and the
 * order the answers are explained in is code's. The queries here deliberately do not filter on the guards
 * they return as columns — a row dropped by a `WHERE` clause cannot later be named as a skipped item.
 */
class FreeUpSpaceRepositoryImpl(
    private val dao: FreeUpSpaceDao,
) : FreeUpSpaceRepository {

    override fun observePlan(): Flow<FreeUpSpacePlan> =
        dao.observeTotals(UploadState.BackedUp.storageKey).map { row ->
            FreeUpSpacePlan(eligibleCount = row.itemCount, reclaimableBytes = row.totalBytes)
        }

    override suspend fun review(limit: Int): List<FreeUpSpaceCandidate> =
        dao.candidates(UploadState.BackedUp.storageKey, limit.coerceAtLeast(1)).map { it.toCandidate() }

    override suspend fun recheck(ids: Collection<Long>): List<EligibilityCheck> {
        val evidence = ids.chunked(MAX_IDS_PER_QUERY)
            .flatMap { dao.eligibilityFor(it) }
            .associate { it.mediaStoreId to it.toEvidence() }
        return FreeUpSpaceEligibility.evaluate(ids, evidence)
    }

    private fun EligibilityRow.toEvidence() = ItemEvidence(
        candidate = FreeUpSpaceCandidate(
            mediaStoreId = mediaStoreId,
            contentUri = contentUri,
            displayName = displayName,
            sizeBytes = sizeBytes,
            mediaType = MediaType.fromStorageKey(mediaType),
        ),
        inIndex = true,
        queueState = queueState?.let(UploadState::fromStorageKey),
        telegramChatId = queueChatId ?: 0L,
        telegramMessageId = queueMessageId ?: 0L,
        hasContentHash = !queueHash.isNullOrBlank(),
        cloudMessagePresent = cloudMessageId != null,
        trashed = trashedAt != 0L,
    )

    private companion object {
        /** The same ceiling every other `IN (…)` in this database respects. */
        const val MAX_IDS_PER_QUERY = 400
    }
}

package com.lumovault.app.domain.usecase

import com.lumovault.app.domain.repository.FreeUpSpaceRepository
import com.lumovault.app.domain.restore.EligibilityCheck
import com.lumovault.app.domain.restore.FreeUpSpacePlan
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.organization.MediaOrganizationRepository
import com.lumovault.app.domain.repository.MediaRepository
import kotlinx.coroutines.flow.Flow

/**
 * Free Up Space's decisions, in the two steps the system's consent prompt forces.
 *
 * ```
 * review  →  user confirms a list  →  prepare (re-check every id)  →  the system asks
 *                                                                   ↓
 *                                                        complete ← RESULT_OK
 * ```
 *
 * [prepare] re-reads each item instead of trusting the review the user was looking at, because the phase
 * requires it and because the gap is real: a cloud sync, a retry, or a delete in the gallery can all move an
 * item between the two taps. [completed] then runs the reconciliation, so the local row and its organisation
 * marks go away while the queue record and the Telegram message do not — which is what leaves the item where
 * PRD section 72 wants it: cloud only, restorable, no longer taking up the phone.
 *
 * The consent request itself is not built here. `MediaStore.createDeleteRequest` returns an `IntentSender`,
 * which only an `Activity` can launch, and the app already keeps that boundary exactly once — in
 * `MediaStoreLocalDeleter`, reached from a view model — so putting it behind a domain interface would add a
 * type that exists only to be forwarded.
 */
class FreeUpSpaceUseCase(
    private val freeUpSpace: FreeUpSpaceRepository,
    private val organization: MediaOrganizationRepository,
    private val media: MediaRepository,
) {
    fun plan(): Flow<FreeUpSpacePlan> = freeUpSpace.observePlan()

    suspend fun review(limit: Int): List<FreeUpSpaceCandidate> = freeUpSpace.review(limit)

    /** What to delete, what not to, and what it would free — all decided now, not at review time. */
    suspend fun prepare(selected: Collection<Long>): PreparedDeletion {
        val checks = freeUpSpace.recheck(selected)
        val deletable = checks.filter { it.isDeletable }
        return PreparedDeletion(
            checks = checks,
            uris = deletable.mapNotNull { it.candidate?.contentUri },
            ids = deletable.mapNotNull { it.candidate?.mediaStoreId },
            reclaimedBytes = deletable.sumOf { it.candidate?.sizeBytes ?: 0L },
        )
    }

    /**
     * The deletion was confirmed. Reconcile, and say how many items left the library.
     *
     * The scan is part of this call rather than left to the next screen: without it, the file is gone from
     * the device while the timeline still draws a thumbnail for it, and a broken thumbnail is the exact
     * thing that makes a user afraid to press the button again.
     */
    suspend fun complete(deletion: PreparedDeletion): Int {
        if (deletion.ids.isEmpty()) return 0
        organization.forgetDeletedLocally(deletion.ids)
        media.sync()
        return deletion.ids.size
    }
}

/**
 * The result of the second check.
 *
 * [skipped] keeps every refusal so the screen can name them: the phase asks for skipped items to be
 * explained, and an item that quietly vanished from a confirmation dialog is the failure mode a user has no
 * way to notice.
 */
data class PreparedDeletion(
    val checks: List<EligibilityCheck>,
    val uris: List<String>,
    val ids: List<Long>,
    val reclaimedBytes: Long,
) {
    val nothingLeft: Boolean
        get() = ids.isEmpty()

    val skipped: List<EligibilityCheck>
        get() = checks.filterNot { it.isDeletable }
}

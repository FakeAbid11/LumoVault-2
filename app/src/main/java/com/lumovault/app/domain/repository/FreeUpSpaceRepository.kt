package com.lumovault.app.domain.repository

import com.lumovault.app.domain.restore.EligibilityCheck
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.restore.FreeUpSpacePlan
import kotlinx.coroutines.flow.Flow

/**
 * The queries behind Free Up Space, and nothing else.
 *
 * The two reads that decide what the user sees are separated on purpose: [review] is a list the screen can
 * bound and the user can scroll, while [observePlan] is an aggregate that must stay exact for a library far
 * larger than that window. Counting the review list would be the tempting shortcut and would be a lie about
 * a hundred-thousand-photo device — the number on the card is what may be freed, not the number of rows the
 * screen happens to hold.
 */
interface FreeUpSpaceRepository {
    fun observePlan(): Flow<FreeUpSpacePlan>

    suspend fun review(limit: Int): List<FreeUpSpaceCandidate>

    /**
     * Every id re-checked, in the order it was asked for.
     *
     * An id with no row left in the index is answered too — as a refusal — rather than dropped, so a caller
     * can tell the user that three of the forty items they picked were already gone.
     */
    suspend fun recheck(ids: Collection<Long>): List<EligibilityCheck>
}

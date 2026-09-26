package com.lumovault.app.ui.backup

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lumovault.app.LumoVaultApplication
import com.lumovault.app.domain.restore.FreeUpSpaceCandidate
import com.lumovault.app.domain.restore.FreeUpSpacePlan
import com.lumovault.app.domain.usecase.PreparedDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where a confirmation got to.
 *
 * [Declined] and [Unavailable] are separate cases because they are separate sentences: the first means the
 * user said no, which is a decision and not an error, and the second means this Android version cannot be
 * asked at all — Phase 7's API 29 case, where the file has to go in the device's own gallery.
 */
sealed interface DeletionOutcome {
    data object None : DeletionOutcome
    data object Asked : DeletionOutcome
    data class Removed(val deleted: Int, val reclaimedBytes: Long) : DeletionOutcome
    data object NothingLeft : DeletionOutcome
    data object Declined : DeletionOutcome
    data object Unavailable : DeletionOutcome
    data object Failed : DeletionOutcome
}

/**
 * Free Up Space's view model: the offer, the review, and one confirmation.
 *
 * What is deliberately absent is a deletion this screen performs. The list the user approves is re-checked by
 * the use case at the moment of confirmation rather than trusted from the review, and the deletion itself goes
 * through the same consent request Phase 7 established — Android shows the user the files and decides. A
 * dismissed dialog therefore leaves every byte in place, and nothing here needs to know that.
 */
class FreeUpSpaceViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as LumoVaultApplication).container

    private val selected = MutableStateFlow(emptySet<Long>())
    private val review = MutableStateFlow<List<FreeUpSpaceCandidate>>(emptyList())
    private val outcome = MutableStateFlow<DeletionOutcome>(DeletionOutcome.None)

    /** The request awaiting an answer, kept only so the screen can hand the same list back after consent. */
    private val pending = MutableStateFlow<PreparedDeletion?>(null)

    /** True while the review has never come back, so the screen can say "loading" rather than guess. */
    private val loading = MutableStateFlow(true)

    /** True while [confirm] is hashing and asking the resolver — the window a second tap would restart. */
    private val confirming = MutableStateFlow(false)

    private val plan = container.freeUpSpace.plan()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FreeUpSpacePlan(0, 0L))

    val state: StateFlow<FreeUpSpaceState> =
        combine(plan, review, selected, outcome, loading) { offered, listed, chosen, last, busy ->
            FreeUpSpaceState(
                plan = offered,
                candidates = listed,
                selected = chosen,
                outcome = last,
                loading = busy,
                selectedBytes = listed.filter { it.mediaStoreId in chosen }.sumOf { it.sizeBytes },
            )
        }
            // The sixth value rides separately: the typed `combine` covers five flows, and the vararg
            // overload would silently turn the five above into an untyped array.
            .combine(confirming) { base, busy -> base.copy(confirming = busy) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), FreeUpSpaceState())

    init {
        loadReview()
    }

    fun refresh() = loadReview()

    private fun loadReview() {
        viewModelScope.launch {
            loading.value = true
            try {
                val listed = container.freeUpSpace.review(REVIEW_LIMIT)
                review.value = listed
                // Selection is clamped to what is still offered. An id the user ticked that is no longer on the
                // list would ride into the confirmation only to be refused there, and a refusal the user never
                // saw is a surprise rather than a safety net.
                val still = listed.map { it.mediaStoreId }.toSet()
                selected.value = selected.value.intersect(still)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // An empty list plus the screen's own "nothing to review" copy is the honest failure state;
                // a spinner that never stops would promise an answer that is not coming.
                Log.w(TAG, "free-up-space review failed: ${error.javaClass.simpleName}")
                review.value = emptyList()
            } finally {
                loading.value = false
            }
        }
    }

    fun toggle(candidate: FreeUpSpaceCandidate) {
        val current = selected.value
        selected.value = if (candidate.mediaStoreId in current) {
            current - candidate.mediaStoreId
        } else {
            current + candidate.mediaStoreId
        }
    }

    fun selectAll() {
        selected.value = review.value.map { it.mediaStoreId }.toSet()
    }

    fun clearSelection() {
        selected.value = emptySet()
    }

    /**
     * Re-checks, then asks Android. The screen launches whatever comes back.
     *
     * Nothing is deleted here, and nothing is deleted on a timer: this call ends with a request the user has
     * to answer, which is the only place a local deletion of somebody's photo is allowed to begin.
     */
    fun confirm() {
        val ids = selected.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            confirming.value = true
            // Preparing reads and hashes real files and the request goes to the resolver: a uri whose
            // grant died, or storage that vanished mid-read, throws. Nothing has been deleted at this
            // point, so the honest outcome is the screen's own "failed" sentence rather than a crash.
            try {
                val prepared = container.freeUpSpace.prepare(ids)
                if (prepared.nothingLeft) {
                    outcome.value = DeletionOutcome.NothingLeft
                    return@launch
                }
                val request = container.localMediaDeleter.requestFor(prepared.uris)
                if (request == null) {
                    outcome.value = DeletionOutcome.Unavailable
                    return@launch
                }
                pending.value = prepared
                outcome.value = DeletionOutcome.Asked
                _pendingConsent.value = request.intentSender
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "free-up-space confirm failed: ${error.javaClass.simpleName}")
                pending.value = null
                outcome.value = DeletionOutcome.Failed
            } finally {
                confirming.value = false
            }
        }
    }

    private val _pendingConsent = MutableStateFlow<android.content.IntentSender?>(null)

    /** The intent the screen hands to its launcher, consumed once launched. */
    val pendingConsent: StateFlow<android.content.IntentSender?> = _pendingConsent.asStateFlow()

    fun consentConsumed() {
        _pendingConsent.value = null
    }

    fun onDeleted() {
        val prepared = pending.value
        pending.value = null
        if (prepared == null) return
        viewModelScope.launch {
            try {
                val deleted = container.freeUpSpace.complete(prepared)
                outcome.value = if (deleted == 0) {
                    DeletionOutcome.NothingLeft
                } else {
                    DeletionOutcome.Removed(deleted = deleted, reclaimedBytes = prepared.reclaimedBytes)
                }
                selected.value = emptySet()
                loadReview()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The device already deleted what it showed the user; only the bookkeeping failed, and
                // the next review recomputes from the rows rather than from this call.
                Log.w(TAG, "free-up-space completion failed: ${error.javaClass.simpleName}")
                outcome.value = DeletionOutcome.Failed
            }
        }
    }

    fun onDeclined() {
        pending.value = null
        outcome.value = DeletionOutcome.Declined
    }

    fun onFailure() {
        pending.value = null
        outcome.value = DeletionOutcome.Failed
    }

    fun dismissOutcome() {
        outcome.value = DeletionOutcome.None
    }

    private companion object {
        private const val TAG = "LumoVaultFreeUpSpace"

        /**
         * How many items the review list can hold.
         *
         * A cap, not a filter: it bounds the list a user scrolls and the `IN (…)` the second check issues,
         * while the headline figures above it stay exact because they are aggregates. Freeing 40 GB out of a
         * 90,000-photo library is then several visits to this screen, which is honest in a way a silent
         * truncation of the total would not be.
         */
        const val REVIEW_LIMIT = 400
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}

/** What the screen draws. Every number on it is an aggregate or a row the user can see. */
data class FreeUpSpaceState(
    val plan: FreeUpSpacePlan = FreeUpSpacePlan(0, 0L),
    val candidates: List<FreeUpSpaceCandidate> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val outcome: DeletionOutcome = DeletionOutcome.None,
    val selectedBytes: Long = 0L,
    val loading: Boolean = false,
    /** A confirmation is being prepared; the button that starts one must not start a second. */
    val confirming: Boolean = false,
) {
    val nothingEligible: Boolean
        get() = plan.eligibleCount == 0

    val allSelected: Boolean
        get() = candidates.isNotEmpty() && selected.size == candidates.size
}

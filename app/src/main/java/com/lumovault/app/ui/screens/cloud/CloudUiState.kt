package com.lumovault.app.ui.screens.cloud

import com.lumovault.app.domain.model.CloudDay
import com.lumovault.app.domain.model.CloudMedia
import com.lumovault.app.domain.model.CloudTypeCount
import com.lumovault.app.domain.model.MediaType
import com.lumovault.app.domain.model.cloudCounts
import com.lumovault.app.domain.model.groupIntoDays
import com.lumovault.app.domain.telegram.CloudFailure
import com.lumovault.app.domain.telegram.CloudInitState
import java.time.ZoneId

/**
 * What the Cloud screen shows. One value, one case at a time — so "your cloud is empty", "still
 * setting up", "signed out" and "Telegram unreachable" cannot all be true on screen at once, which is
 * exactly what a handful of unrelated Booleans would allow.
 */
sealed interface CloudUiState {
    /** Nothing asked for yet. */
    data object Idle : CloudUiState

    /** No TDLib binary or no API credentials: this build cannot reach Telegram at all. */
    data object NotAvailable : CloudUiState

    /** Signed out or still handshaking. Not an error, and not a reason to reopen onboarding. */
    data object NeedsSignIn : CloudUiState

    data class Preparing(val step: Step) : CloudUiState {
        enum class Step { Searching, Validating, Creating }
    }

    /** First index of an empty-so-far cloud. [found] is a real row count, never a percentage. */
    data class Scanning(val found: Int) : CloudUiState

    /** Channel exists and holds nothing. */
    data object NoMedia : CloudUiState

    data class Library(
        val days: List<CloudDay>,
        val counts: List<Pair<MediaType, Int>>,
        val totalCount: Int,
        /** The rows on screen came from the cached index rather than from a completed read. */
        val fromCache: Boolean,
        /**
         * The reason for that, narrowed to the one case where the cause is knowable.
         *
         * [fromCache] alone answers "are these rows current", which is what the derivation needs to decide
         * to keep showing the library instead of replacing a healthy list with an error. It does not
         * answer *why*, and the screen used it to print one sentence: "You're offline". Three different
         * situations reach that state — Telegram unreachable, a build with no Telegram, and a specific
         * failure such as a rejected channel marker — and only the first of them is offline. Saying
         * "offline" for the other two sends the user to check a Wi-Fi connection that was never the
         * problem. So the honest claim is a separate flag, true only where the app knows it.
         */
        val offline: Boolean = false,
        /** A refresh is running while the existing library stays visible. */
        val refreshing: Boolean,
        /** Message ids that also exist on the device: backed-up rather than cloud-only. */
        val localMatches: Set<Long>,
        /** More rows exist than the current window, so scrolling has somewhere to go. */
        val hasMoreToLoad: Boolean,
    ) : CloudUiState

    data class Failed(val failure: CloudFailure) : CloudUiState
}

/**
 * Chooses the Cloud state from the initialization machine plus what the index currently holds.
 *
 * The two decisions worth testing live here: an index that already has rows is never replaced by a
 * spinner, and a failed sync with rows on disk reads as offline rather than as an error, because the
 * library itself is not wrong — only its freshness is.
 */
fun deriveCloudState(
    init: CloudInitState,
    items: List<CloudMedia>,
    totalCount: Int,
    counts: List<CloudTypeCount>,
    localMatches: Set<Long> = emptySet(),
    refreshingOverride: Boolean = false,
    zone: ZoneId = ZoneId.systemDefault(),
): CloudUiState {
    fun library(fromCache: Boolean, refreshing: Boolean, offline: Boolean = false) = CloudUiState.Library(
        days = items.groupIntoDays(zone),
        counts = counts.cloudCounts(),
        totalCount = totalCount,
        fromCache = fromCache,
        offline = offline,
        refreshing = refreshing || refreshingOverride,
        localMatches = localMatches,
        hasMoreToLoad = totalCount > items.size,
    )

    val hasCachedLibrary = totalCount > 0 && items.isNotEmpty()

    // The preparing steps are a spinner only when there is nothing to show. With a cached index the
    // library stays on screen wearing the neutral "checking" pill — the rendering Scanning already gives —
    // because every tab entry and pull-to-refresh walks through ValidatingChannel, and a full-screen
    // "Preparing…" there replaced a healthy library for the length of a network round trip. `fromCache`
    // stays false on purpose: nothing has said Telegram was unreachable yet, and the offline pill is
    // reserved for the states that actually know it.
    fun preparing(step: CloudUiState.Preparing.Step) =
        if (hasCachedLibrary) library(fromCache = false, refreshing = true) else CloudUiState.Preparing(step)

    return when (init) {
        CloudInitState.Idle -> CloudUiState.Idle

        // offline stays false: this build has no Telegram at all, which is not a network condition,
        // and telling the user to check their connection would name a cause the app knows is false.
        CloudInitState.TelegramUnavailable -> if (hasCachedLibrary) {
            library(fromCache = true, refreshing = false)
        } else {
            CloudUiState.NotAvailable
        }

        CloudInitState.WaitingForTelegram -> if (hasCachedLibrary) {
            library(fromCache = true, refreshing = false)
        } else {
            CloudUiState.NeedsSignIn
        }

        CloudInitState.SearchingChannel -> preparing(CloudUiState.Preparing.Step.Searching)
        CloudInitState.ValidatingChannel -> preparing(CloudUiState.Preparing.Step.Validating)
        CloudInitState.CreatingChannel -> preparing(CloudUiState.Preparing.Step.Creating)

        is CloudInitState.Scanning -> if (hasCachedLibrary) {
            library(fromCache = false, refreshing = true)
        } else {
            CloudUiState.Scanning(init.found)
        }

        // The one place offline is true: Telegram itself said it could not be reached.
        CloudInitState.Offline -> if (hasCachedLibrary) {
            library(fromCache = true, offline = true, refreshing = false)
        } else {
            CloudUiState.Failed(CloudFailure(CloudFailure.Kind.RequestFailed))
        }

        CloudInitState.Ready -> when {
            // The index has rows but the window query has not emitted them yet. Showing "no media"
            // here would tell the user their cloud library had vanished.
            totalCount > 0 && items.isEmpty() -> CloudUiState.Preparing(CloudUiState.Preparing.Step.Searching)
            hasCachedLibrary -> library(fromCache = false, refreshing = false)
            else -> CloudUiState.NoMedia
        }

        // Likewise: a rejected marker or an unusable channel is not an offline phone, and the failure
        // carries its own kind precisely so the screen can stop guessing.
        is CloudInitState.Failed -> if (hasCachedLibrary) {
            library(fromCache = true, refreshing = false)
        } else {
            CloudUiState.Failed(init.failure)
        }
    }
}

/**
 * Whether the Cloud tab should ask, in its own words, for the sign-in it cannot do without.
 *
 * [CloudUiState.NeedsSignIn] alone: the one state where there is nothing on screen *and* a door still open.
 * [CloudUiState.NotAvailable] is excluded because a build without TDLib has no sign-in to offer, which is
 * the same reason its placeholder carries no button — a dialog pointing at a screen that cannot work is
 * worse than silence. A cached `Library` is excluded because that person is reading their own photos and
 * the offline line above them already tells the truth about freshness.
 *
 * [dismissedThisVisit] is per visit rather than per installation: the interruption returns the next time the
 * tab is opened while the account is still missing, and stops the moment it is not.
 */
fun shouldOfferConnectDialog(state: CloudUiState, dismissedThisVisit: Boolean): Boolean =
    !dismissedThisVisit && state is CloudUiState.NeedsSignIn

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
        /** Telegram could not be reached, so what is on screen is the cached index. */
        val fromCache: Boolean,
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
    fun library(fromCache: Boolean, refreshing: Boolean) = CloudUiState.Library(
        days = items.groupIntoDays(zone),
        counts = counts.cloudCounts(),
        totalCount = totalCount,
        fromCache = fromCache,
        refreshing = refreshing || refreshingOverride,
        localMatches = localMatches,
        hasMoreToLoad = totalCount > items.size,
    )

    val hasCachedLibrary = totalCount > 0 && items.isNotEmpty()

    return when (init) {
        CloudInitState.Idle -> CloudUiState.Idle

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

        CloudInitState.SearchingChannel -> CloudUiState.Preparing(CloudUiState.Preparing.Step.Searching)
        CloudInitState.ValidatingChannel -> CloudUiState.Preparing(CloudUiState.Preparing.Step.Validating)
        CloudInitState.CreatingChannel -> CloudUiState.Preparing(CloudUiState.Preparing.Step.Creating)

        is CloudInitState.Scanning -> if (hasCachedLibrary) {
            library(fromCache = false, refreshing = true)
        } else {
            CloudUiState.Scanning(init.found)
        }

        CloudInitState.Offline -> if (hasCachedLibrary) {
            library(fromCache = true, refreshing = false)
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

        is CloudInitState.Failed -> if (hasCachedLibrary) {
            library(fromCache = true, refreshing = false)
        } else {
            CloudUiState.Failed(init.failure)
        }
    }
}

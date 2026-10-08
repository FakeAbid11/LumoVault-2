package com.lumovault.app.ui.screens.photos

import com.lumovault.app.domain.model.Media
import com.lumovault.app.domain.model.MediaAccessStatus
import com.lumovault.app.domain.model.MediaDay

/**
 * The Photos screen's whole state, as one type.
 *
 * A bag of `isLoading` / `isEmpty` / `hasError` booleans can describe combinations that cannot
 * happen — empty *and* loading *and* errored — and the UI has to guess which one wins. Each case
 * below is a state the screen can actually be in, so the rendering is a `when`.
 */
sealed interface PhotosUiState {
    /** MediaAccessStatus has not been read yet; nothing is claimed and nothing is requested. */
    data object CheckingAccess : PhotosUiState

    /** The library cannot be built without media access, so this is the honest first screen. */
    data object PermissionRequired : PhotosUiState

    /** Access is granted and there is nothing indexed yet — the very first scan is running. */
    data object Scanning : PhotosUiState

    /**
     * The library count has not come back from Room yet.
     *
     * Its own state because `0` cannot carry both meanings. Room's `COUNT(*)` answers `0` for a device
     * with no media *and* for a query that has not run, and a flow that starts at its initial value has to
     * pick one of them — picking zero printed "No photos yet" over a library of thousands, on the first
     * frame and again every time the subscription lapsed and the flow reset. Nothing is claimed here
     * either: not that there is media, and not that there is none.
     */
    data object CheckingIndex : PhotosUiState

    /** The scan finished and the device genuinely has no supported media. */
    data object Empty : PhotosUiState

    /**
     * The library is on screen. [isRefreshing] keeps the existing rows visible while a rescan runs,
     * which is what the PRD asks for: an already-indexed library must not be replaced by a spinner.
     * [refreshFailed] is the other half of that promise — a rescan that threw leaves the rows on
     * screen and stale, and without flagging it the failure was invisible: a spinner that never
     * appeared and a grid that quietly stopped being current.
     */
    data class Content(
        val days: List<MediaDay>,
        val indexedCount: Int,
        val totalCount: Int,
        val isRefreshing: Boolean,
        val limitedAccess: Boolean,
        val refreshFailed: Boolean,
    ) : PhotosUiState {
        val hasMoreToLoad: Boolean get() = indexedCount < totalCount
    }

    /** [retryAllowed] is false for a scan that failed before anything could be indexed. */
    data class Failure(val reason: Reason) : PhotosUiState {
        enum class Reason { ScanFailed }
    }
}

/**
 * Derives the screen state from its inputs. Pure and `internal` so the rules — not the rendering —
 * are unit-tested, including the one that matters most: an empty library and a library that has
 * not been scanned yet are different states with different copy.
 */
internal fun derivePhotosState(
    access: MediaAccessStatus,
    items: List<Media>,
    totalCount: Int?,
    isScanning: Boolean,
    scanFailed: Boolean,
    days: List<MediaDay>,
): PhotosUiState = when {
    access == MediaAccessStatus.Unknown -> PhotosUiState.CheckingAccess

    !access.allowsScanning -> PhotosUiState.PermissionRequired

    // The count is still out. This has to come before every branch that reads it as a number, because
    // `null` and `0` are different facts about the same device and only one of them is "no photos".
    totalCount == null -> PhotosUiState.CheckingIndex

    // A failed scan with nothing indexed is an error; with rows already present the library is
    // still there to show, and Content flags the failed refresh so the grid can say the rows are
    // from the last scan rather than leaving that for the user to notice.
    scanFailed && totalCount == 0 -> PhotosUiState.Failure(PhotosUiState.Failure.Reason.ScanFailed)

    isScanning && totalCount == 0 -> PhotosUiState.Scanning

    totalCount == 0 -> PhotosUiState.Empty

    else -> PhotosUiState.Content(
        days = days,
        indexedCount = items.size,
        totalCount = totalCount,
        isRefreshing = isScanning,
        limitedAccess = access == MediaAccessStatus.PartiallyGranted,
        // Only while idle: a scan in flight has already reset the flag when it starts, and the
        // brief overlap of "failed" and "still winding down" must not show a stale notice under
        // the spinner that is already retrying.
        refreshFailed = scanFailed && !isScanning,
    )
}

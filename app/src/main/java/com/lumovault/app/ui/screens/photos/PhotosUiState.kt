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

    /** The scan finished and the device genuinely has no supported media. */
    data object Empty : PhotosUiState

    /**
     * The library is on screen. [isRefreshing] keeps the existing rows visible while a rescan runs,
     * which is what the PRD asks for: an already-indexed library must not be replaced by a spinner.
     */
    data class Content(
        val days: List<MediaDay>,
        val indexedCount: Int,
        val totalCount: Int,
        val isRefreshing: Boolean,
        val limitedAccess: Boolean,
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
    totalCount: Int,
    isScanning: Boolean,
    scanFailed: Boolean,
    days: List<MediaDay>,
): PhotosUiState = when {
    access == MediaAccessStatus.Unknown -> PhotosUiState.CheckingAccess

    !access.allowsScanning -> PhotosUiState.PermissionRequired

    // A failed scan with nothing indexed is an error; with rows already present it is a stale
    // library, which Content already reports through isRefreshing.
    scanFailed && totalCount == 0 -> PhotosUiState.Failure(PhotosUiState.Failure.Reason.ScanFailed)

    isScanning && totalCount == 0 -> PhotosUiState.Scanning

    totalCount == 0 -> PhotosUiState.Empty

    else -> PhotosUiState.Content(
        days = days,
        indexedCount = items.size,
        totalCount = totalCount,
        isRefreshing = isScanning,
        limitedAccess = access == MediaAccessStatus.PartiallyGranted,
    )
}

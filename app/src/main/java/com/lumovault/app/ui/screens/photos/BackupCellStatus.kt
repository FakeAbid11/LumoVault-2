package com.lumovault.app.ui.screens.photos

import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.UploadState

/**
 * What a grid cell shows about one item's backup, and nothing else.
 *
 * The internal queue has six states; a thumbnail can carry one glyph. Collapsing them is a rendering
 * decision, so it lives here as a pure function rather than inside the cell, where it could not be
 * tested — and rather than in [UploadState], which must not know that a screen exists.
 *
 * Five marks rather than four: `Queued` is separated from `Preparing` on purpose, because "in line" and
 * "being copied to a sendable file" are different waits, and an item sitting in `Preparing` for a long
 * time is information the user needs to distinguish a queue from a stall.
 */
enum class BackupCellStatus {
    /** Never backed up. */
    Unbacked,

    Queued,
    Preparing,
    Uploading,
    BackedUp,
    Failed,
}

fun backupCellStatus(state: UploadState?): BackupCellStatus = when (state) {
    // No row is the common case and means nothing has been queued; PRD section 48's NOT_BACKED_UP is
    // the absence of a record rather than a stored state.
    null, UploadState.Cancelled -> BackupCellStatus.Unbacked
    UploadState.Queued -> BackupCellStatus.Queued
    UploadState.Preparing -> BackupCellStatus.Preparing
    UploadState.Uploading -> BackupCellStatus.Uploading
    UploadState.BackedUp -> BackupCellStatus.BackedUp
    UploadState.Failed -> BackupCellStatus.Failed
}

/**
 * The queue's own state, kept apart from [PhotosUiState] rather than folded into it.
 *
 * The timeline is already built from five combined flows, which is the largest typed `combine` Kotlin
 * offers: a sixth would resolve through the varargs overload and produce an `Array<Any>`, quietly
 * breaking the derivation. A second flow costs the screen one extra `collect` and leaves that trap
 * where the next person cannot fall into it.
 */
data class BackupOverview(
    val states: Map<Long, UploadState> = emptyMap(),
    val summary: BackupQueueSummary = BackupQueueSummary(),
) {
    val selectionEnabled: Boolean get() = summary.isActive

    fun statusOf(mediaId: Long): BackupCellStatus = backupCellStatus(states[mediaId])
}

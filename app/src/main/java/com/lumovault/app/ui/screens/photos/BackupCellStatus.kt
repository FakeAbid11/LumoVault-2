package com.lumovault.app.ui.screens.photos

import androidx.annotation.StringRes
import com.lumovault.app.R
import com.lumovault.app.domain.backup.BackupFailureKind
import com.lumovault.app.domain.backup.BackupQueueSummary
import com.lumovault.app.domain.backup.UploadState

/**
 * What a grid cell shows about one item's backup, and nothing else.
 *
 * The internal queue has seven states; a thumbnail can carry one glyph. Collapsing them is a rendering
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
    // Two ways of saying the same thing to a thumbnail. No row at all is still the common case, and a row
    // that only carries an identity says nothing more than "this content is here and nothing stores it" —
    // PRD section 48's NOT_BACKED_UP, and section 13's ☁ either way. What recognition's record *is* for is
    // the next scan, not this glyph.
    null, UploadState.Cancelled, UploadState.NotBackedUp -> BackupCellStatus.Unbacked
    UploadState.Queued -> BackupCellStatus.Queued
    UploadState.Preparing -> BackupCellStatus.Preparing
    UploadState.Uploading -> BackupCellStatus.Uploading
    UploadState.BackedUp -> BackupCellStatus.BackedUp
    UploadState.Failed -> BackupCellStatus.Failed
}

/**
 * The words for why an item stopped, one per kind the queue can record.
 *
 * A pure mapping rather than text carried from Telegram, and that is the point: the stored value is an enum
 * name chosen at the upload boundary, where the server's own message is discarded — so what appears on a
 * screen is LumoVault's sentence about a failure, never a string someone else's service wrote.
 *
 * Null in, null out. A row that failed without a recorded reason draws the plain "Failed" and nothing else;
 * picking the nearest excuse for it would be the app claiming it knows something it does not.
 */
@StringRes
fun backupFailureReasonRes(kind: BackupFailureKind?): Int? = when (kind) {
    null -> null
    BackupFailureKind.SourceMissing -> R.string.backup_failure_source_missing
    BackupFailureKind.SourceUnreadable -> R.string.backup_failure_source_unreadable
    BackupFailureKind.SourceChanged -> R.string.backup_failure_source_changed
    BackupFailureKind.InsufficientSpace -> R.string.backup_failure_insufficient_space
    BackupFailureKind.ChannelUnavailable -> R.string.backup_failure_channel_unavailable
    BackupFailureKind.NotAuthenticated -> R.string.backup_failure_not_authenticated
    BackupFailureKind.Network -> R.string.backup_failure_network
    BackupFailureKind.RateLimited -> R.string.backup_failure_rate_limited
    BackupFailureKind.Rejected -> R.string.backup_failure_rejected
    BackupFailureKind.Unknown -> R.string.backup_failure_unknown
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

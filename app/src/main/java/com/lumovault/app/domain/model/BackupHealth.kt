package com.lumovault.app.domain.model

/**
 * The library's backup state as one read, for the screens that have to tell the truth about it.
 *
 * Every number here is a database aggregate. None of them is derived from what the screen is looking at,
 * because a screen shows a window and the library is not a window: a page of 300 items cannot answer how
 * many of 90,000 are stored.
 *
 * The reason this is a model rather than six flows at the call site is that the two screens reading it have
 * to agree, and the sentence "everything is backed up" has to be computed once, in one place, from all of it:
 * [allLocalMediaBackedUp] is false if anything is waiting, failed, or unaccounted for — not just when
 * [backedUp] happens to be the biggest number on the screen.
 */
data class BackupHealth(
    val localTotal: Int,
    val backedUp: Int,
    /** Queued, or recognised as needing a send and not yet asked for one. */
    val waiting: Int,
    /** Preparing or uploading: work a worker owns right now. */
    val uploading: Int,
    val failed: Int,
    /** Remote records with no local item behind them — including every photo freed up from this device. */
    val cloudOnly: Int,
    val reclaimableCount: Int,
    val reclaimableBytes: Long,
    val lastBackupSeconds: Long?,
    val lastScanSeconds: Long?,
) {
    val pending: Int
        get() = waiting + uploading

    /**
     * Local items nothing has proven are stored anywhere else.
     *
     * Clamped at zero rather than trusted: a queue row can exist for an item the index dropped a moment ago,
     * and a negative count on a health screen is worse than an imprecise one.
     */
    val notBackedUp: Int
        get() = (localTotal - backedUp).coerceAtLeast(0)

    val neverScanned: Boolean
        get() = lastScanSeconds == null || lastScanSeconds == 0L

    val neverBackedUp: Boolean
        get() = lastBackupSeconds == null || lastBackupSeconds == 0L

    /**
     * The only condition under which a LumoVault screen may say everything is safe.
     *
     * An empty library reports false, which is right: "all zero of your photos are backed up" is not a
     * reassurance, and the empty state has its own sentence.
     */
    val allLocalMediaBackedUp: Boolean
        get() = localTotal > 0 && backedUp == localTotal && pending == 0 && failed == 0 && notBackedUp == 0

    /** Anything a "view issues" affordance should lead with. */
    val hasIssues: Boolean
        get() = failed > 0 || notBackedUp > 0 || pending > 0
}

package com.lumovault.app.data.local.mediastore

import com.lumovault.app.data.local.media.MediaEntity

/**
 * What one read of the device's media index concluded.
 *
 * Three answers, because there are three and the scanner used to hand back one value for all of them. The
 * number of rows a scan returned is not the question a prune asks; the question is whether the provider was
 * able to answer at all, and a count cannot carry that.
 *
 * This is the rule Phase 9 already established for finding a Telegram channel — found, conclusively absent, or
 * could not conclude — with the polarity inverted, because the destructive act runs the other way: an empty
 * answer from TDLib must not *create* a channel, and an empty answer from MediaStore must not *delete* an
 * index. Which of the three may remove rows is therefore the whole content of this type.
 */
internal sealed interface MediaIndexScan {
    /** The provider answered with rows, each already tagged with the scan id that will be pruned against. */
    data class Found(val rows: List<MediaEntity>) : MediaIndexScan

    /**
     * A cursor that opened and held nothing.
     *
     * An answer rather than a failure. A person who deletes all forty thousand photos did have no photos, and
     * an index that kept showing them would draw an empty library and offer to back up files that are gone. So
     * this removes rows exactly as [Found] does — which is why it is a separate case and not something folded
     * into [CouldNotConclude] to be safe.
     */
    data object EmptyLibrary : MediaIndexScan

    /**
     * The query could not be made: `ContentResolver.query` answers null, which is how a failing provider says
     * so while a permission is held and nothing is actually wrong with the library.
     *
     * Nothing may be removed on the strength of this. `media` is rebuilt by the next scan, but favourites,
     * archive marks, trash timestamps and album memberships are not derivable from MediaStore, and the three
     * orphan sweeps are written as `NOT IN (SELECT media_store_id FROM media)` — a test that is true of every
     * row in all three tables the moment `media` is empty. One transient failure, inside one committed
     * transaction, was enough to destroy the user's decisions about their own library permanently.
     */
    data object CouldNotConclude : MediaIndexScan
}

/** Whether this answer is a basis for removing rows the scan did not see. */
internal fun MediaIndexScan.prunesIndex(): Boolean = this !is MediaIndexScan.CouldNotConclude

/**
 * Where the index reads the device.
 *
 * One member, and the return type is the only reason it is an interface rather than the concrete class:
 * `MediaStoreDataSource` needs a `ContentResolver`, which nothing off-device can supply, so without a seam the
 * statement "an inconclusive scan leaves the user's data alone" cannot be tested by anyone — and that statement
 * is the whole difference between this file and a data-loss bug.
 */
internal fun interface MediaIndexSource {
    suspend fun scan(scanId: Long): MediaIndexScan
}

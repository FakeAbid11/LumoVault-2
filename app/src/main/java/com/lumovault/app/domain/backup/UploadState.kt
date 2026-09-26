package com.lumovault.app.domain.backup

/**
 * Where a backup record has got to. PRD section 48's list, with one state still absent.
 *
 * `VERIFYING` is the one left out on purpose: nothing in this build confirms a stored object by reading
 * it back from Telegram, and a row that entered a state only to be displayed would be a claim the code
 * cannot support. What Phase 6 does instead is stronger and plainer — a backup carries its own content
 * hash in the channel, and a later index scan that reads that hash back is the confirmation, recorded in
 * [BackedUp] rather than in a state that never leaves.
 *
 * `HASHING` is absent for a different reason: hashing is a step *about* an item, not something the item
 * waits in. A row with no hash and no attempted hash is a candidate; the work is bounded by the
 * recognition pass rather than by a state a crash could leave a row stuck in.
 *
 * [NotBackedUp] exists because Phase 6 needs it. Before content identity there was nothing to record for
 * an item the user never queued, so "not backed up" was exactly the absence of a row. Now a hashed item
 * earns a record whether or not it was ever queued — that record is what stops the next scan re-hashing
 * a four-gigabyte video — and a row that describes a file with no remote home has to *say* it is without
 * one. PRD section 48 names the state; section 13's ☁ glyph is what it renders as.
 */
enum class UploadState(val storageKey: String) {
    /** Identity is known and nothing in the channel carries it: eligible, not yet asked to upload. */
    NotBackedUp("not_backed_up"),

    /** Accepted into the queue, waiting for its turn. */
    Queued("queued"),

    /** Bytes are being copied from MediaStore to a path TDLib can read. */
    Preparing("preparing"),

    /** `sendMessage` is in flight; TDLib uploads the file as part of sending it. */
    Uploading("uploading"),

    /** Telegram created the message, and [BackupRequest.telegramMessageId] says which one. */
    BackedUp("backed_up"),

    /** Stopped, with [BackupRequest.failure] saying why in terms this enum can express. */
    Failed("failed"),

    /** Asked to stop before it left the queue. */
    Cancelled("cancelled"),
    ;

    /** True while a worker owns the row and is making progress or about to. */
    val isInFlight: Boolean
        get() = this == Preparing || this == Uploading

    companion object {
        /** Unknown keys become [Failed] rather than [Queued]: re-queueing a row this build cannot
         * name would send work to Telegram that nobody authorised. */
        fun fromStorageKey(key: String?): UploadState =
            entries.firstOrNull { it.storageKey == key } ?: Failed
    }
}

/**
 * The legal moves. Enforced at the boundary that writes state, not only in the UI that reads it,
 * because a row that goes `Failed` → `BackedUp` without an upload in between would make the ✓ on a
 * thumbnail a lie — and nothing else in the app would notice.
 *
 * Recognition does not travel through this table. It moves a row to [UploadState.BackedUp] from states
 * that never went near an upload — including [UploadState.Queued] — and that is correct only because it
 * has evidence a state machine cannot check: a message already sitting in the user's channel whose
 * manifest carries this row's hash. So the adoption write carries its own guard in SQL (never on a row
 * that is [Preparing] or [Uploading], because a scan must not land on top of a send in flight), and
 * adding `Queued to BackedUp` here would only hand the ordinary upload path a way to fake a success.
 */
object UploadTransitions {
    private val allowed: Set<Pair<UploadState, UploadState>> = setOf(
        UploadState.Queued to UploadState.Preparing,
        UploadState.Queued to UploadState.Cancelled,
        UploadState.Queued to UploadState.Failed,

        UploadState.Preparing to UploadState.Uploading,
        UploadState.Preparing to UploadState.Failed,
        UploadState.Preparing to UploadState.Queued,

        // Recognition found the content already in the channel before this worker sent a byte, so the row
        // is settled by the message that already holds it. Only a row the worker owns may take this step,
        // and only with a message id read out of the remote index — which is why [UploadState.Queued] has
        // no such edge: nobody owns a queued row, and an unowned row cannot be closed by an assertion
        // nobody is standing behind.
        UploadState.Preparing to UploadState.BackedUp,

        UploadState.Uploading to UploadState.BackedUp,
        UploadState.Uploading to UploadState.Failed,
        // A send that dies with the process is not a user failure: recovery re-queues it.
        UploadState.Uploading to UploadState.Queued,

        UploadState.Failed to UploadState.Queued,

        // A recognized item the user then asks to back up. Recognition put the row here; the tap moves it
        // into the queue, and nothing about the identity already recorded changes.
        UploadState.NotBackedUp to UploadState.Queued,

        // PRD section 71: the file behind a completed backup became different content, so the message
        // that holds the old bytes is no longer this item's backup. The remote copy stays.
        UploadState.BackedUp to UploadState.NotBackedUp,
    )

    fun isLegal(from: UploadState, to: UploadState): Boolean = from == to || (from to to) in allowed
}

/**
 * Why a backup stopped, in LumoVault's words.
 *
 * The raw TDLib message never lands here: like `TelegramAuthFailure`, this type exists so a token
 * that happens to quote a file name or a chat cannot reach a screen or a log line. [retryable]
 * carries the policy, because which of these the app should try again is a product decision, not
 * something to infer from an error string at the call site.
 */
enum class BackupFailureKind(val retryable: Boolean) {
    /** MediaStore no longer has the file — deleted on the device, or never granted to us. */
    SourceMissing(retryable = false),

    /** The bytes exist but could not be opened or copied. */
    SourceUnreadable(retryable = false),

    /**
     * The file changed while it was being read, so what was hashed and what was staged are different
     * content. Retryable because the next pass re-identifies it: the new bytes may need no upload at all,
     * and sending the old hash over them would be a manifest that lies.
     */
    SourceChanged(retryable = true),

    /** Not enough free space left for a staging copy of this item. */
    InsufficientSpace(retryable = false),

    /** The storage channel is gone, unreachable, or no longer ours. */
    ChannelUnavailable(retryable = false),

    /** Signed out, or the session died mid-queue. */
    NotAuthenticated(retryable = true),

    /** Connection lost or refused; the item may already be half-sent. */
    Network(retryable = true),

    /** Telegram asked for a pause (`FLOOD_WAIT_<n>`). */
    RateLimited(retryable = true),

    /** Telegram refused this file. Retrying a refusal just earns another refusal. */
    Rejected(retryable = false),

    /** Something this build cannot classify; bounded by the attempt cap rather than retried forever. */
    Unknown(retryable = true),
    ;
}

data class BackupFailure(val kind: BackupFailureKind) {
    val retryable: Boolean get() = kind.retryable
}

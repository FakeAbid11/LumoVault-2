package com.lumovault.app.domain.backup

/**
 * Where a backup request has got to. PRD section 48's example list, with two of its states removed.
 *
 * `HASHING` and `VERIFYING` are absent on purpose: hashing is Phase 6 and nothing in this phase
 * verifies a stored object against a hash, so a row could enter either state and never legitimately
 * leave it. A state that only exists to be displayed is a claim the code cannot support — the queue
 * says `UPLOADING` until the message really exists, and then says `BACKED_UP`.
 *
 * `NOT_BACKED_UP` is not a row either. Most of a library is never queued, and storing a state for
 * every un-queued item would duplicate the media index for information that is already exactly one
 * query away: the absence of a row.
 */
enum class UploadState(val storageKey: String) {
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
 */
object UploadTransitions {
    private val allowed: Set<Pair<UploadState, UploadState>> = setOf(
        UploadState.Queued to UploadState.Preparing,
        UploadState.Queued to UploadState.Cancelled,
        UploadState.Queued to UploadState.Failed,

        UploadState.Preparing to UploadState.Uploading,
        UploadState.Preparing to UploadState.Failed,
        UploadState.Preparing to UploadState.Queued,

        UploadState.Uploading to UploadState.BackedUp,
        UploadState.Uploading to UploadState.Failed,
        // A send that dies with the process is not a user failure: recovery re-queues it.
        UploadState.Uploading to UploadState.Queued,

        UploadState.Failed to UploadState.Queued,
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

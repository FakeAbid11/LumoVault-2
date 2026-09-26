package com.lumovault.app.domain.restore

/**
 * Where one "download this original" request has got to.
 *
 * A separate vocabulary from [com.lumovault.app.domain.backup.UploadState], because it describes the
 * opposite direction of travel and is answered by different evidence: an upload is settled by Telegram
 * creating a message, a restore by bytes landing in MediaStore. They share no table for that reason, and
 * sharing names across two things that mean different things is how a `preparing` row stops being
 * answerable without reading which of the two it belongs to.
 *
 * [Pending] rather than "queued" because the UI word is *Preparing*, and this row is created the moment
 * the user asks — before any transfer has been attempted. A request that never started is still a fact
 * worth showing after a restart, which is the whole reason this is a table and not a ViewModel field.
 */
enum class RestoreState(val storageKey: String) {
    Pending("pending"),
    Downloading("downloading"),

    /** Hashing what landed, and checking its length against what Telegram said the file is. */
    Verifying("verifying"),

    /** Writing into MediaStore, which is the only step that makes the file visible to the rest of Android. */
    Saving("saving"),

    Completed("completed"),
    Failed("failed"),
    Cancelled("cancelled"),
    ;

    /** True while work is expected to be happening, so the UI may show a live progress bar. */
    val isLive: Boolean
        get() = this == Pending || this == Downloading || this == Verifying || this == Saving

    companion object {
        /**
         * An unknown key becomes [Failed], like the backup queue does.
         *
         * The alternative — defaulting to something live — would leave a row the app keeps waiting on, and
         * a screen showing a progress bar for a transfer that stopped days ago is a lie with an animation.
         */
        fun fromStorageKey(key: String?): RestoreState =
            entries.firstOrNull { it.storageKey == key } ?: Failed
    }
}

/**
 * Why a restore stopped, in LumoVault's words.
 *
 * Raw TDLib text never reaches here, for the same reason it never reaches `BackupFailureKind`: an error
 * string from the server can quote a file name, a chat, or a token. [retryable] is the policy, stated
 * per case rather than inferred from a message at the call site.
 */
enum class RestoreFailureKind(val retryable: Boolean) {
    /** Telegram could not resolve the stored remote id — the message is gone, or is no longer readable. */
    SourceGone(retryable = false),

    /** Signed out, or the session died mid-transfer. */
    NotAuthenticated(retryable = true),

    /** Connection lost or refused mid-download. */
    Network(retryable = true),

    /** Telegram asked for a pause (`FLOOD_WAIT_<n>`). */
    RateLimited(retryable = true),

    /** Not enough free space for the download and the MediaStore copy it becomes. */
    InsufficientSpace(retryable = false),

    /**
     * The bytes landed short of the size TDLib reported, or could not be read back.
     *
     * Retryable: a truncated transfer is usually the same transfer tried again, and the file is removed
     * before this is recorded, so a retry cannot read the bad bytes.
     */
    Incomplete(retryable = true),

    /** MediaStore refused the insert, or the row it created is not the one we wrote. */
    SaveRejected(retryable = true),

    /** Asked to stop. */
    Cancelled(retryable = true),

    /** Something this build cannot classify. */
    Unknown(retryable = true),
    ;
}

/** What a restore ended as, with the reason in a form a screen can translate. */
data class RestoreFailure(val kind: RestoreFailureKind) {
    val retryable: Boolean get() = kind.retryable
}

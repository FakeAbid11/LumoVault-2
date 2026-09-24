package com.lumovault.app.domain.telegram

/**
 * The channel's answer when LumoVault asks whether a chat id is still its storage.
 *
 * Kept as a closed set because each case needs a different recovery: [Valid] continues, [NotFound]
 * and [NotOwned] mean rediscover, [MarkerMissing] means the name matched but the channel is not ours
 * and adopting it would put this user's backups into a stranger's chat.
 */
sealed interface CloudChannelVerdict {
    data object Valid : CloudChannelVerdict

    /** The chat id is gone — deleted, left, or never reachable from this account. */
    data object NotFound : CloudChannelVerdict

    /** Reachable, but not a broadcast channel: a group, a user, or a forum. */
    data object NotAChannel : CloudChannelVerdict

    /** Right name, no marker. Never adopted, and never auto-created over the top of it. */
    data object MarkerMissing : CloudChannelVerdict

    /** Marker from a protocol newer than this build can speak. */
    data class MarkerTooNew(val version: Int) : CloudChannelVerdict

    /** This account does not administer the channel, so it cannot be its owner's storage. */
    data object NotOwned : CloudChannelVerdict
}

/**
 * A channel LumoVault has adopted, and the account it belongs to.
 *
 * [ownerUserId] is what makes a swapped Telegram account invalidate the association (PRD section 73)
 * rather than quietly browse someone else's library.
 */
data class CloudAssociation(
    val chatId: Long,
    val ownerUserId: Long,
    val protocolVersion: Int,
    /** Last message id reached by a scan; the resume cursor for a paged walk (PRD section 28). */
    val lastScannedMessageId: Long = 0,
    val lastSyncSeconds: Long = 0,
)

/** The whole cloud start-up flow as one value, so no screen can render two contradictory steps. */
sealed interface CloudInitState {
    /** Nothing has been asked for yet. */
    data object Idle : CloudInitState

    /** Waiting on Telegram sign-in; not an error, and onboarding must not restart (PRD section 35). */
    data object WaitingForTelegram : CloudInitState

    /** No TDLib binary or no API credentials in this build. Reported, never dressed up as success. */
    data object TelegramUnavailable : CloudInitState

    data object SearchingChannel : CloudInitState
    data object CreatingChannel : CloudInitState
    data object ValidatingChannel : CloudInitState

    /**
     * Reading history. [found] is how many remote items the index holds right now — a real count from
     * the database, never an estimate or a percentage.
     */
    data class Scanning(val found: Int) : CloudInitState

    data object Ready : CloudInitState

    /** Last sync attempt could not reach Telegram. The cached index stays visible. */
    data object Offline : CloudInitState

    data class Failed(val failure: CloudFailure) : CloudInitState
}

/** Why cloud start-up stopped, with the kind deciding what the UI offers next. */
data class CloudFailure(
    val kind: Kind,
    /** TDLib's numeric code, kept for the log line only; never shown as-is. */
    val statusCode: Int = 0,
) {
    enum class Kind {
        NotAuthenticated,
        ChannelUnusable,
        ChannelCreationFailed,
        MarkerRejected,

        /** Telegram asked for a pause. Nothing is wrong with the library, so it is not an error. */
        RateLimited,
        RequestFailed,
        Unexpected,
    }
}

/**
 * A Telegram-side failure, translated at the TDLib boundary.
 *
 * Raw TDLib error text never travels with it: the message can quote a chat title or a phone number,
 * so the type carries only [CloudFailure] — the same rule [TdErrorMapper] applies to authentication.
 */
class CloudFailureException(
    val failure: CloudFailure,
    cause: Throwable? = null,
) : Exception("cloud request failed: ${failure.kind}", cause)

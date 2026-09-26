package com.lumovault.app.domain.telegram

import com.lumovault.app.domain.model.CloudMedia

/**
 * Everything LumoVault asks of Telegram on the cloud side, with no TDLib types crossing the line —
 * the same rule [TelegramAuthRepository] follows, so the Cloud screen never learns how TDLib is
 * driven and a build without the binary fails at this boundary rather than in a Composable.
 */
interface TelegramCloudRepository {
    /** False when TDLib is absent or unconfigured; callers must then show "not available". */
    val isUsable: Boolean

    /** `getMe` — the identifier the saved channel association is checked against. */
    suspend fun accountUserId(): Long

    /**
     * The account's storage channel, or the conclusion that it has none.
     *
     * Deliberately not a nullable chat id, which is what this method used to return: `null` conflated "this
     * account has no storage channel" with "TDLib has not told me about its chats yet", and the caller's
     * answer to both was to create one. On a reinstall — where the TDLib database is as fresh as the Room
     * file — that conflation cost people their whole cloud library, which stays in the original channel
     * while the app browses an empty new one.
     *
     * A channel is still only adopted on evidence, never on a name: broadcast type, this account's
     * ownership, and a supported marker.
     */
    suspend fun discoverStorageChannel(): ChannelDiscovery

    suspend fun validateChannel(chatId: Long): CloudChannelVerdict

    /** Creates the channel, writes the marker, and returns its chat id. */
    suspend fun createStorageChannel(): Long

    /**
     * One page of history, newest first, without requesting any file.
     *
     * @param fromMessageId `0` starts at the newest message; afterwards pass the previous page's
     *   [CloudHistoryPage.oldestMessageId].
     */
    suspend fun loadHistoryPage(chatId: Long, fromMessageId: Long, limit: Int): CloudHistoryPage
}

/**
 * A page of remote media.
 *
 * [reachedBeginning] is decided by TDLib's answer rather than by a page count, so a channel whose
 * history is sparse still terminates. [totalRemoteCount] is `messages.total_count` when Telegram
 * reports it, which is what lets the UI show a real number instead of a fabricated percentage.
 */
data class CloudHistoryPage(
    val media: List<CloudMedia>,
    val oldestMessageId: Long,
    val reachedBeginning: Boolean,
    val totalRemoteCount: Int? = null,
)

package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.flow.Flow
import org.drinkless.tdlib.TdApi

/**
 * The client shape the repositories code against: one typed TDLib request object in, its typed
 * answer back, plus the updates TDLib sends unprompted.
 *
 * This is the first line TDLib's own types appear on, and the last — above it the app works in
 * LumoVault's models. Typing the boundary rather than stringing it is what makes a request Telegram
 * never advertised impossible to write, and a field that changed name between TDLib releases a
 * compile error instead of a login that silently never finishes.
 */
interface TelegramClient {
    /** False when this build has no TDLib binary or no API credentials. */
    val isUsable: Boolean

    /** Responses that were not asked for, chiefly [TdApi.UpdateAuthorizationState]. */
    val updates: Flow<TdApi.Object>

    /** Creates this process's TDLib client. Repeat calls do nothing. */
    suspend fun start()

    /**
     * Sends one request and waits for the object answering it.
     *
     * [timeoutMillis] is a caller-supplied ceiling because "how long is too long" is not one number
     * across TDLib's requests: reading a page of metadata should not take a minute, while sending a
     * file of several hundred megabytes legitimately takes as long as the upload does. A request that
     * is still legitimately working is not a failure, and cutting one off at a metadata-sized timeout
     * would report a successful backup as lost.
     *
     * @throws TelegramRequestException when TDLib answers with [TdApi.Error].
     */
    suspend fun <T : TdApi.Object> request(
        function: TdApi.Function<T>,
        timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): T

    companion object {
        /** Enough for TDLib's own first-run database setup, and short enough to notice a hang. */
        const val DEFAULT_TIMEOUT_MILLIS = 120_000L
    }
}

/**
 * A TDLib error. [reason] is the machine token (`AUTH_CODE_INVALID`, `FLOOD_WAIT_312`); it is kept
 * for diagnostics and mapped by [TdErrorMapper] before anything reaches the user, because the raw
 * reason is Telegram's text, not ours, and is not guaranteed to be free of the number being dialled.
 */
class TelegramRequestException(
    val code: Int,
    val reason: String,
) : Exception("TDLib request failed (code $code): $reason")

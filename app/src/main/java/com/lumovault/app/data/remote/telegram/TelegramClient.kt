package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.flow.Flow
import org.drinkless.tdlib.TdApi

/**
 * The client shape the repositories code against: one typed TDLib request object in, its typed
 * answer back, plus the updates TDLib sends unprompted.
 *
 * `org.drinkless.tdlib` deliberately stops at this line — the shape is typed rather than stringly so
 * no caller can send a method Telegram never advertised, and so a field that changed name between
 * TDLib releases fails at compile time instead of at login.
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
     * @throws TelegramRequestException when TDLib answers with [TdApi.Error].
     */
    suspend fun <T : TdApi.Object> request(function: TdApi.Function<T>): T
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

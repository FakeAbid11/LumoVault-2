package com.lumovault.app.data.remote.telegram

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * The client shape the authentication repository codes against: JSON requests out, JSON responses
 * and unsolicited updates back. Nothing above this line knows TDLib's wire format.
 */
interface TelegramClient {
    /** False when this build has no TDLib binary or no API credentials. */
    val isUsable: Boolean

    /** Responses that were not asked for, chiefly `updateAuthorizationState`. */
    val updates: Flow<JsonObject>

    suspend fun start()

    /**
     * Sends one request and waits for the response carrying the same `@extra`.
     *
     * @throws TelegramRequestException when TDLib answers with an `error` object.
     */
    suspend fun request(method: String, params: JsonObject = buildJsonObject { }): JsonObject

    suspend fun stop()
}

/**
 * A TDLib error. [reason] is the machine token (`AUTH_CODE_INVALID`, `FLOOD_WAIT_312`); it is kept
 * for diagnostics and mapped by [TdErrorMapper] before anything reaches the user, because the raw
 * reason is Telegram's text, not ours, and is not guaranteed to be free of the number being dialled.
 */
class TelegramRequestException(
    val code: Int,
    val method: String,
    val reason: String,
) : Exception("TDLib request '$method' failed (code $code): $reason")

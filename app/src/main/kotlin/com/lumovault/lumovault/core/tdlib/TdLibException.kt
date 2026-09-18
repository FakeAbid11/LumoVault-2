package com.lumovault.lumovault.core.tdlib

import org.drinkless.tdlib.TdApi

/**
 * Semantic Telegram error codes.
 *
 * **The load-bearing detail**: TDLib's [TdApi.Error.code] is an HTTP-style
 * number (400/401/429). The *semantic* identifier (`PHONE_NUMBER_INVALID`,
 * `FLOOD_WAIT_30`) lives in [TdApi.Error.message]. Code that switched on the
 * numeric code matched nothing and silently failed every request.
 *
 * Ported from lib/core/tdlib/tdlib_exception.dart. Matching is by **prefix**,
 * not equality, because flood waits carry a dynamic suffix (`FLOOD_WAIT_30`).
 */
object TdLibErrors {

    private val FLOOD_WAIT = Regex("FLOOD_WAIT_(\\d+)")

    /** Extracts the semantic code: TDLib's error message, not its numeric code. */
    fun semanticCode(error: TdApi.Error): String =
        error.message.takeIf { it.isNotEmpty() } ?: error.code.toString()

    /** Seconds to wait, when Telegram is rate-limiting us. Null if not a flood wait. */
    fun retryAfterSeconds(error: TdApi.Error): Int? {
        val code = semanticCode(error)
        FLOOD_WAIT.find(code)?.let { return it.groupValues[1].toIntOrNull() }
        // Some servers phrase it in the human-readable text instead.
        Regex("retry after (\\d+)").findAll(error.message.lowercase())
            .lastOrNull()?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    /**
     * A message safe to show the user. Falls back to the raw TDLib message,
     * which is often technical but always accurate.
     */
    fun userFacingMessage(error: TdApi.Error): String {
        val code = semanticCode(error)
        return when {
            code.startsWith("FLOOD_WAIT") -> {
                val seconds = retryAfterSeconds(error)
                if (seconds != null) "Telegram is rate limiting. Please wait $seconds seconds."
                else "Telegram is rate limiting. Please wait a moment."
            }
            code == "PHONE_INVALID" || code == "PHONE_NUMBER_INVALID" ->
                "Invalid phone number. Please check and try again."
            code == "PHONE_CODE_INVALID" || code == "CODE_INVALID" ->
                "Wrong code. Please try again."
            code == "PHONE_CODE_EXPIRED" ->
                "The code has expired. Please request a new one."
            code == "PASSWORD_HASH_INVALID" || code == "PASSWORD_INVALID" ->
                "Wrong password. Please try again."
            code == "PASSWORD_TOO_SHORT" || code == "PASSWORD_TOO_LONG" ->
                "Password length is not accepted."
            code == "NETWORK_ERROR" || code == "TIMEOUT" ->
                "Network error. Please check your connection."
            code == "AUTH_KEY_UNREGISTERED" || code == "AUTH_KEY_INVALID" ->
                "Session expired. Please log in again."
            code == "USER_DEACTIVATED" || code == "USER_DEACTIVATED_BAN" ->
                "This account has been deactivated. Please contact Telegram support."
            code == "CHANNEL_PRIVATE" || code == "CHAT_ADMIN_REQUIRED" ->
                "This channel is private or requires admin rights."
            code == "STORAGE_FULL" || code == "FILE_TOO_BIG" ->
                "Telegram storage is full, or the file is too large to upload."
            code == "FILE_NOT_FOUND" -> "The file could not be found."
            code == "API_ID_INVALID" ->
                "This app is not registered with Telegram. Backups cannot run."
            code == "PHONE_NUMBER_BANNED" -> "This phone number is banned."
            code == "AUTH_REGISTRATION_DELTA" -> "Too many attempts. Please try later."
            else -> error.message.ifEmpty { "Unknown Telegram error." }
        }
    }
}

/**
 * A Telegram error surfaced as an exception.
 *
 * [code] is the *semantic* identifier (see [TdLibErrors]); [message] is the
 * technical detail. [displayMessage] is what the UI should show.
 */
class TdLibException(
    val code: String,
    message: String,
    val displayMessage: String = userFacingMessageFor(code, message),
) : Exception(message.ifEmpty { code }) {

    val isFloodWait: Boolean get() = code.startsWith("FLOOD_WAIT")
    val isAuthExpired: Boolean
        get() = code == "AUTH_KEY_UNREGISTERED" || code == "AUTH_KEY_INVALID"

    companion object {
        fun from(error: TdApi.Error): TdLibException =
            TdLibException(code = TdLibErrors.semanticCode(error), message = error.message)

        private fun userFacingMessageFor(code: String, message: String): String =
            TdLibErrors.userFacingMessage(TdApi.Error(0, message.ifEmpty { code }))
    }
}

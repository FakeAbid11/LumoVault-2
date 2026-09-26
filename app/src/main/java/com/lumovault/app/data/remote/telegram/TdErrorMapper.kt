package com.lumovault.app.data.remote.telegram

import com.lumovault.app.domain.telegram.TelegramAuthFailure

/**
 * Translates a TDLib error into something the UI can show.
 *
 * TDLib's messages are machine tokens such as `PHONE_NUMBER_INVALID` or `FLOOD_WAIT_312` — the two
 * fields of a [org.drinkless.tdlib.TdApi.Error], which [TelegramRequestException] carries. Mapping
 * them here rather than passing `message` through is what keeps a raw response containing a phone
 * number or session detail away from the screen, and it turns rate limits into an actual "try again
 * in a few minutes" instead of a mystery failure.
 */
object TdErrorMapper {
    fun from(code: Int, message: String): TelegramAuthFailure {
        val token = message.uppercase()

        FLOOD_WAIT.find(token)?.groupValues?.get(1)?.toIntOrNull()?.let { seconds ->
            return TelegramAuthFailure(TelegramAuthFailure.Kind.TooManyRequests, retryAfterSeconds = seconds)
        }

        val kind = when {
            "PHONE_NUMBER_INVALID" in token || "PHONE_NUMBER_BANNED" in token ->
                TelegramAuthFailure.Kind.InvalidPhoneNumber

            "AUTH_CODE_EXPIRED" in token || "CODE_EXPIRED" in token || "SMS_CODE_EXPIRED" in token ->
                TelegramAuthFailure.Kind.CodeExpired

            "AUTH_CODE_INVALID" in token || "CODE_INVALID" in token || "AUTH_CODE_UNEXPECTED" in token ->
                TelegramAuthFailure.Kind.InvalidCode

            "PASSWORD_HASH_INVALID" in token || "PASSWORD_INVALID" in token ->
                TelegramAuthFailure.Kind.PasswordIncorrect

            "SESSION_REVOKED" in token || "AUTH_KEY_UNREGISTERED" in token ||
                "AUTH_KEY_INVALID" in token || "USER_DEACTIVATED" in token ->
                TelegramAuthFailure.Kind.SessionInvalid

            "NETWORK" in token || "TIMED OUT" in token || "RESOLVE" in token ||
                "SOCKET" in token || code == 502 || code == 503 || code == 504 ->
                TelegramAuthFailure.Kind.NetworkUnavailable

            else -> TelegramAuthFailure.Kind.Unexpected
        }

        return TelegramAuthFailure(kind)
    }

    private val FLOOD_WAIT = Regex("""FLOOD_WAIT_(\d+)""")
}
